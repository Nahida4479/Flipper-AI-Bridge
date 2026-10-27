import struct
import sys
from pathlib import Path

MAGIC = b"CBDICT01"


def main():
    fap = Path(sys.argv[1])
    en = Path(sys.argv[2]).read_bytes()
    pl = Path(sys.argv[3]).read_bytes()
    data = fap.read_bytes()
    if data[-16:-8] == MAGIC:
        en_len, pl_len = struct.unpack("<II", data[-8:])
        data = data[: len(data) - 16 - en_len - pl_len]
    fap.write_bytes(data + en + pl + MAGIC + struct.pack("<II", len(en), len(pl)))
    print(fap, len(data) + len(en) + len(pl) + 16)


main()
