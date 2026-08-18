"""Mirror of the Android SearchResult / NetDiskType / ResourceCategory.

Kept identical so a future FastAPI service can deserialise legacy
APK payloads and a future migration path stays trivial.
"""

from __future__ import annotations

from enum import Enum
from typing import Optional

from pydantic import BaseModel, Field


class NetDiskType(str, Enum):
    BAIDU = "baidu"
    QUARK = "quark"
    XUNLEI = "xunlei"
    ALIYUN = "aliyun"
    YUNPAN123 = "123"
    DIRECT_URL = "direct_url"
    OTHER = "other"


class ResourceCategory(str, Enum):
    ALL = "all"
    EBOOK = "ebook"
    NETDISK = "netdisk"


class FailureKind(str, Enum):
    NETWORK = "network"
    TIMEOUT = "timeout"
    SOURCE_DOWN = "source_down"
    PARSE = "parse"
    EMPTY = "empty"
    UNKNOWN = "unknown"


class SearchResult(BaseModel):
    id: str
    title: str
    description: str = ""
    url: str
    netDiskType: NetDiskType = NetDiskType.OTHER
    size: str = ""
    date: str = ""
    sourceUrl: str = ""
    sourceName: str
    sourceId: str
    category: ResourceCategory = ResourceCategory.ALL
    fileType: str = ""
    isValid: bool = True
    requiresWebView: bool = False
    extractionCode: Optional[str] = None


class SourceFailure(BaseModel):
    message: str
    kind: FailureKind = FailureKind.UNKNOWN
