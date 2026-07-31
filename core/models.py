from __future__ import annotations

from dataclasses import dataclass
from typing import Dict


@dataclass
class ValidationFinding:
    level: str
    section: str
    message: str

    def to_dict(self) -> Dict[str, str]:
        return {
            "level": self.level,
            "section": self.section,
            "message": self.message,
        }
