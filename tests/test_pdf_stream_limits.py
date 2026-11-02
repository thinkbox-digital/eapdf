import base64
import os
import unittest
from unittest.mock import patch
import zlib
import pikepdf
from pdf_stream_limits import Budget, CURRENT, DecodeLimitError, read_stream


class PdfStreamLimitTests(unittest.TestCase):
    def test_small_compressed_bomb_and_false_size_never_return_partial_bytes(self):
        with pikepdf.Pdf.new() as pdf:
            stream = pdf.make_stream(zlib.compress(b"A" * 1024**2))
            stream.Filter = pikepdf.Name.FlateDecode
            stream.DL = 1
            with self.assertRaises(DecodeLimitError): read_stream(stream, 4096)
            self.assertEqual(b"A" * 1024**2, read_stream(stream, 1024**2))

    def test_common_filter_chains_are_byte_exact(self):
        expected = b"binary\0payload\xff" * 20
        with pikepdf.Pdf.new() as pdf:
            stream = pdf.make_stream(base64.a85encode(zlib.compress(expected)) + b"~>")
            stream.Filter = pikepdf.Array([pikepdf.Name.ASCII85Decode, pikepdf.Name.FlateDecode])
            self.assertEqual(expected, read_stream(stream, 4096))

    def test_aggregate_limits_count_repeated_reads(self):
        with patch.dict(os.environ, {"VALIDATOR_MAX_DECODED_BYTES": "12"}), pikepdf.Pdf.new() as pdf:
            token = CURRENT.set(Budget())
            try:
                stream = pdf.make_stream(b"12345678")
                self.assertEqual(b"12345678", read_stream(stream, 8))
                with self.assertRaises(DecodeLimitError): read_stream(stream, 8)
            finally: CURRENT.reset(token)

    def test_unsupported_filters_and_predictors_are_explicitly_unavailable(self):
        with pikepdf.Pdf.new() as pdf:
            stream = pdf.make_stream(b"not decoded")
            stream.Filter = pikepdf.Name.JBIG2Decode
            with self.assertRaises(DecodeLimitError): read_stream(stream, 100)
            stream.Filter = pikepdf.Name.FlateDecode
            stream.DecodeParms = pikepdf.Dictionary(Predictor=12, Columns=2**31-1)
            with self.assertRaises(DecodeLimitError): read_stream(stream, 100)
