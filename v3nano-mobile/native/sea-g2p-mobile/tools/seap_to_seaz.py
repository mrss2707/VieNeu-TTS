"""Convert sea_g2p.bin (SEAP v1/v2) -> SEAZ v1: vi-only, block-compressed, random-access.

Keeps EVERY merged (vi+en) and common entry verbatim (lossless for the Vietnamese
pipeline). Drops only the Thai/Indonesian sections, which the vi pipeline never reads.

SEAZ v1 layout (little-endian):
  0   "SEAZ"
  4   u32 version = 1
  8   u32 block_entries
  12  u32 merged_count
  16  u32 common_count
  20  u32 zdict_off     24 u32 zdict_len
  28  u32 merged_idx_off 32 u32 merged_nblocks
  36  u32 common_idx_off 40 u32 common_nblocks
  44  u32 reserved
  48  ... zstd dictionary, block data, index tables
Index entry: u32 key_len, key bytes (first key of block), u32 data_off, u32 comp_len, u32 raw_len.
Block raw payload: merged = key\\0val\\0 ... ; common = key\\0vi\\0en\\0 ...
Entries are sorted by UTF-8 bytes (same order as Rust &str).

usage: seap_to_seaz.py in.bin out.seaz [block_entries=64]
"""
import pathlib
import struct
import sys

import zstandard as zstd

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1] / "src-upstream/scripts"))
import seap  # noqa: E402

ZDICT_SIZE = 110_000
LEVEL = 19


def build_blocks(rows, n):
    return [b"".join(rows[i:i + n]) for i in range(0, len(rows), n)]


def main():
    src, dst = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])
    n = int(sys.argv[3]) if len(sys.argv) > 3 else 64
    merged, common, _sections = seap.load_bin(src)
    by = lambda kv: kv[0].encode("utf-8")
    m = sorted(merged.items(), key=by)
    c = sorted(common.items(), key=by)
    m_rows = [f"{k}\0{v}\0".encode("utf-8") for k, v in m]
    c_rows = [f"{k}\0{v}\0{e}\0".encode("utf-8") for k, (v, e) in c]
    for k, v in m:
        assert "\0" not in k and "\0" not in v
    m_blocks, c_blocks = build_blocks(m_rows, n), build_blocks(c_rows, n)
    m_first = [m[i][0] for i in range(0, len(m), n)]
    c_first = [c[i][0] for i in range(0, len(c), n)]

    samples = m_blocks[:: max(1, len(m_blocks) // 4000)][:4000] + c_blocks
    zdict = zstd.train_dictionary(ZDICT_SIZE, samples).as_bytes()
    cctx = zstd.ZstdCompressor(level=LEVEL, dict_data=zstd.ZstdCompressionDict(zdict), write_content_size=True)

    out = bytearray(b"\0" * 48)
    zd_off = len(out)
    out += zdict

    def emit(blocks, firsts):
        index = bytearray()
        for blk, key in zip(blocks, firsts):
            comp = cctx.compress(blk)
            off = len(out)
            out.extend(comp)
            kb = key.encode("utf-8")
            index += struct.pack("<I", len(kb)) + kb + struct.pack("<III", off, len(comp), len(blk))
        return index

    m_index = emit(m_blocks, m_first)
    c_index = emit(c_blocks, c_first)
    m_idx_off = len(out)
    out += m_index
    c_idx_off = len(out)
    out += c_index
    struct.pack_into("<4sIIIIIIIIIII", out, 0, b"SEAZ", 1, n, len(m), len(c), zd_off, len(zdict),
                     m_idx_off, len(m_blocks), c_idx_off, len(c_blocks), 0)
    dst.write_bytes(bytes(out))
    print(f"merged={len(m)} common={len(c)} blocks={len(m_blocks)}+{len(c_blocks)} "
          f"zdict={len(zdict)/1e3:.0f}KB -> {dst} {len(out)/1e6:.2f} MB")


if __name__ == "__main__":
    main()
