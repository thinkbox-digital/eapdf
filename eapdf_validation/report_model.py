"""Report accumulation and outcomes, independent of PDF parsing or engine execution.

The root public Report binds the rulebook/diagnostic policy through four hooks.
Instances retain their existing state fields and serialization shape.
"""
from abc import ABC, abstractmethod
from collections import defaultdict


class ReportModel(ABC):
    @staticmethod
    @abstractmethod
    def _rulebook_hash():
        """Rulebook identity captured when a report is created."""

    @staticmethod
    @abstractmethod
    def _covered_rules():
        """Rules delegated to the external PDF/A engine."""

    @staticmethod
    @abstractmethod
    def _rule_codes():
        """Current rule ordering for the collapsed summary."""

    @staticmethod
    @abstractmethod
    def _diagnostic_value(value):
        """Convert diagnostic values without changing the source payload."""

    def __init__(self,path):
        self.path=path
        self.rulebook_sha256=self._rulebook_hash()
        self.items=[]
        self.counts=defaultdict(int)
        self.seen_codes=set()
        self.delegated_codes=set()
    def delegates(self,code):
        return code in self.delegated_codes
    def add(self,level,code,msg,details=None):
        if code in self._covered_rules():
            return
        self.items.append({"level":level,"code":code,"message":self._diagnostic_value(msg),"details":self._diagnostic_value(details)})
        self.counts[level]+=1
        self.seen_codes.add(code)
    def ok(self,code,msg,details=None): self.add("PASS",code,msg,details)
    def warn(self,code,msg,details=None): self.add("WARN",code,msg,details)
    def fail(self,code,msg,details=None): self.add("FAIL",code,msg,details)
    def na(self,code,msg,details=None): self.add("N/A",code,msg,details)
    def rule_summary(self):
        order={"FAIL":3,"WARN":2,"PASS":1,"N/A":0}
        collapsed={}
        for item in self.items:
            code=item["code"]
            if code not in collapsed or order[item["level"]]>order[collapsed[code]]:
                collapsed[code]=item["level"]
        counts=defaultdict(int)
        for code in self._rule_codes():
            counts[collapsed.get(code,"N/A")]+=1
        return {
            "total": len(self._rule_codes()),
            "pass": counts["PASS"],
            "warn": counts["WARN"],
            "fail": counts["FAIL"],
            "na": counts["N/A"],
        }
    def summary(self):
        rules=self.rule_summary()
        return {
            "file":self.path,
            "rulebook_sha256":self.rulebook_sha256,
            "pass":self.counts["PASS"],
            "warn":self.counts["WARN"],
            "fail":self.counts["FAIL"],
            "na":self.counts["N/A"],
            "rule_pass":rules["pass"],
            "rule_warn":rules["warn"],
            "rule_fail":rules["fail"],
            "rule_na":rules["na"],
            "rule_total":rules["total"],
            "status":("FAIL" if self.counts["FAIL"] else
                      "INCOMPLETE" if any(item["code"]=="VALIDATION_INCOMPLETE" and item["level"]=="WARN"
                                          for item in self.items) else "PASS")
        }
