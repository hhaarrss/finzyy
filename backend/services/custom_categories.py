"""
Rules for a user's own spending categories.

Kept apart from the router so the rules can be tested without a database: what a name may look
like, which names are taken by the built-in categories, and how many a user may have.
"""

import re
import unicodedata
from typing import Iterable, List

from categorizer.transaction_categorizer import CANONICAL_CATEGORY_MAP
from constants.categories import CREDIT_CATEGORIES, DEBIT_CATEGORIES
from services.transaction_aggregates import EXCLUDED_CATEGORY_PLACEHOLDERS

MAX_CUSTOM_CATEGORIES = 20
MAX_NAME_LENGTH = 30
MIN_NAME_LENGTH = 2

# Built-in names, the spellings the categoriser folds into them ("food" -> Food & Dining), and
# the "not sorted yet" placeholders. A custom category with one of these names would either be
# silently merged into the built-in one or hidden from every total.
RESERVED_NAMES = (
    {c.lower() for c in DEBIT_CATEGORIES + CREDIT_CATEGORIES}
    | {k.lower() for k in CANONICAL_CATEGORY_MAP}
    | {p.lower() for p in EXCLUDED_CATEGORY_PLACEHOLDERS}
    | {"needs review", "needs_review", "miscellaneous", "unassigned", "uncategorized", "uncategorised"}
)

# Besides letters and digits in any script: spaces and the few marks people put in a label.
_PUNCTUATION = set(" &'/.+-")


def _allowed(ch: str) -> bool:
    # Combining marks (category M*) are part of the letters in Devanagari, Tamil and others.
    return ch.isalnum() or ch in _PUNCTUATION or unicodedata.category(ch).startswith("M")

# Built-in spending categories that stay at the end of the list, after the user's own.
_CATCH_ALLS = ("Transfer", "Other")


class CategoryNameError(ValueError):
    """The name can't be used; the message is written for the user."""


def clean_name(raw: str) -> str:
    """Trims and validates a name, returning it as it should be stored."""
    name = re.sub(r"\s+", " ", (raw or "")).strip()
    if len(name) < MIN_NAME_LENGTH:
        raise CategoryNameError("Give the category a name of at least 2 characters.")
    if len(name) > MAX_NAME_LENGTH:
        raise CategoryNameError(f"Keep the name to {MAX_NAME_LENGTH} characters or fewer.")
    if not all(_allowed(ch) for ch in name):
        raise CategoryNameError("Use letters, numbers, spaces and & ' / . + - only.")
    if not any(ch.isalnum() for ch in name):
        raise CategoryNameError("The name needs at least one letter or number.")
    if name.lower() in RESERVED_NAMES:
        raise CategoryNameError(f"\"{name}\" is already a built-in category.")
    return name


def name_key(name: str) -> str:
    """How two names are compared: case does not make a different category."""
    return name.lower()


def spending_list(custom_names: Iterable[str]) -> List[str]:
    """Built-in spending categories with the user's own slotted in before Transfer and Other."""
    built_in = [c for c in DEBIT_CATEGORIES if c not in _CATCH_ALLS]
    tail = [c for c in DEBIT_CATEGORIES if c in _CATCH_ALLS]
    return built_in + list(custom_names) + tail
