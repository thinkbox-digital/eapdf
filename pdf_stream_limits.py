"""Bounded generalized stream decoding for validator attachments and metadata.

Rendering/image filters and uncommon encodings are reported as unavailable;
they never fall back to an unbounded native decode or silently use raw bytes.
"""
import base64
from contextvars import ContextVar
from functools import wraps
import os
import zlib
from validation_worker import memory_allowance_bytes


class DecodeLimitError(ValueError):
    def __init__(self, message):
        super().__init__(message)
        budget = CURRENT.get()
        if budget is not None:
            budget.limited = message


def setting(name, fallback):
    if name in ("VALIDATOR_MAX_STREAM_BYTES", "VALIDATOR_MAX_METADATA_BYTES"):
        fallback = max(1, memory_allowance_bytes() // (16 if name.endswith("METADATA_BYTES") else 4))
    return max(1, int(os.environ.get(name, fallback)))


CURRENT = ContextVar("pdf_decoding_budget", default=None)


class Budget:
    def __init__(self):
        self.remaining = setting("VALIDATOR_MAX_DECODED_BYTES", 1) if os.environ.get("VALIDATOR_MAX_DECODED_BYTES") else None
        self.files = 0
        self.limited = None

    def charge(self, size):
        if self.remaining is None:
            return
        if size > self.remaining:
            raise DecodeLimitError("Aggregate decoded-stream byte limit reached")
        self.remaining -= size


def bounded_validation(function):
    @wraps(function)
    def run(pdf, report, *args, **kwargs):
        token = CURRENT.set(Budget()) if CURRENT.get() is None else None
        try:
            return function(pdf, report, *args, **kwargs)
        except DecodeLimitError as error:
            report.warn("VALIDATION_INCOMPLETE", "Inspection stopped at a security limit", {"reason": str(error)})
            return report
        finally:
            if CURRENT.get().limited:
                report.warn("VALIDATION_INCOMPLETE", "A security limit prevented complete stream inspection",
                            {"reason": CURRENT.get().limited})
            if token is not None:
                CURRENT.reset(token)
    return run


def count_file():
    budget = CURRENT.get()
    if budget is not None:
        budget.files += 1
        if os.environ.get("VALIDATOR_MAX_EMBEDDED_FILES") and budget.files > setting("VALIDATOR_MAX_EMBEDDED_FILES", 1):
            raise DecodeLimitError("Embedded-file count limit reached")


def read_stream(stream, maximum):
    raw = memoryview(stream.get_raw_stream_buffer())
    if len(raw) > max(maximum * 2, 65536):
        raise DecodeLimitError("Encoded PDF stream exceeds its byte limit")
    filters = stream.get("/Filter", None)
    filters = [] if filters is None else list(filters) if isinstance(filters, (list, tuple)) or getattr(filters, "_type_name", "") == "array" else [filters]
    if len(filters) > 8:
        raise DecodeLimitError("PDF stream filter-chain limit reached")
    parameters = stream.get("/DecodeParms", None)
    parameters = list(parameters) if getattr(parameters, "_type_name", "") == "array" else [parameters] * len(filters)
    data = raw
    budget = CURRENT.get()

    def decode(data, name, params):
        output = bytearray()

        def emit(value):
            if len(value) > maximum - len(output):
                raise DecodeLimitError("PDF stream exceeds its decoded-byte limit")
            if budget is not None:
                budget.charge(len(value))
            output.extend(value)

        if name in ("/FlateDecode", "/Fl"):
            if params is not None and int(params.get("/Predictor", 1)) != 1:
                raise DecodeLimitError("Predictor decoding is unavailable under the bounded stream policy")
            decoder = zlib.decompressobj()
            for offset in range(0, len(data), 65536):
                pending = data[offset:offset + 65536]
                while pending:
                    chunk = decoder.decompress(pending, min(65536, maximum - len(output) + 1))
                    emit(chunk)
                    pending = decoder.unconsumed_tail
            if not decoder.eof:
                raise ValueError("Truncated Flate stream")
        elif name in ("/ASCIIHexDecode", "/AHx"):
            digits = bytearray()
            for value in data:
                if value == 62:
                    break
                if value in b"\x00\t\n\x0c\r ":
                    continue
                digits.append(value)
                if len(digits) == 8192:
                    emit(bytes.fromhex(digits.decode("ascii"))); digits.clear()
            if len(digits) % 2:
                digits.append(48)
            emit(bytes.fromhex(digits.decode("ascii")))
        elif name in ("/ASCII85Decode", "/A85"):
            group = bytearray(); ended = False
            for value in data:
                if ended:
                    if value != 62:
                        raise ValueError("Invalid ASCII85 terminator")
                    break
                if value in b"\x00\t\n\x0c\r ":
                    continue
                if value == 126:
                    ended = True; continue
                if value == 122:
                    if group:
                        raise ValueError("Invalid ASCII85 zero group")
                    emit(b"\0" * 4); continue
                group.append(value)
                if len(group) == 5:
                    emit(base64.a85decode(group)); group.clear()
            if len(group) == 1:
                raise ValueError("Invalid ASCII85 final group")
            if group:
                emit(base64.a85decode(group))
        elif name in ("/RunLengthDecode", "/RL"):
            offset = 0
            while offset < len(data):
                count = data[offset]; offset += 1
                if count == 128:
                    break
                length = count + 1 if count < 128 else 1
                if offset + length > len(data):
                    raise ValueError("Truncated RunLength stream")
                emit(data[offset:offset + length] if count < 128 else bytes([data[offset]]) * (257 - count))
                offset += length
        elif name is None:
            for offset in range(0, len(data), 65536):
                emit(data[offset:offset + 65536])
        else:
            raise DecodeLimitError("Unsupported bounded stream filter: " + name)
        return output

    if not filters:
        data = decode(data, None, None)
    for index, name in enumerate(filters):
        data = decode(data, str(name), parameters[index] if index < len(parameters) else None)
    return bytes(data)
