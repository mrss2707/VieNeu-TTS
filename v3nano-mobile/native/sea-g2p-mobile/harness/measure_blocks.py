"""Measure block-compressed size of the vi dictionary (merged+common only) at several block sizes/codecs."""
import sys, pathlib, time, lzma, zlib
import zstandard as zstd, lz4.block
up = pathlib.Path(__file__).resolve().parents[1] / "src-upstream/scripts"
sys.path.insert(0, str(up)); import seap
D = pathlib.Path(sys.argv[1])
merged, common, sections = seap.load_bin(D)
print("merged", len(merged), "common", len(common), "sections", {k: len(v) for k, v in sections.items()})
by = lambda kv: kv[0].encode()
m = sorted(merged.items(), key=by)
c = sorted(common.items(), key=by)
rows = [f"{k}\0{v}\0".encode() for k, v in m]
raw = sum(map(len, rows)); print("merged raw MB %.1f" % (raw / 1e6))
def blocks(n): return [b"".join(rows[i:i+n]) for i in range(0, len(rows), n)]
for n in (32, 64, 128, 256):
    bl = blocks(n)
    idx = sum(len(m[i][0].encode()) + 5 for i in range(0, len(m), n))  # first-key index (key + u32 offset)
    res = {}
    t = lambda f: sum(len(f(b)) for b in bl)
    res["zlib9"] = t(lambda b: zlib.compress(b, 9))
    res["lz4hc"] = t(lambda b: lz4.block.compress(b, mode="high_compression", compression=12))
    res["zstd19"] = t(lambda b: zstd.ZstdCompressor(level=19).compress(b))
    # trained dictionary on a sample of blocks
    samples = bl[::max(1, len(bl)//4000)][:4000]
    zd = zstd.train_dictionary(110_000, samples)
    cz = zstd.ZstdCompressor(level=19, dict_data=zd)
    res["zstd19+dict"] = t(cz.compress) + len(zd.as_bytes())
    print(f"block={n:4d} entries ({len(bl)} blocks, index {idx/1e6:.2f} MB): " + "  ".join(f"{k} {v/1e6:5.2f}MB" for k, v in res.items()))
# decode speed with the 64-entry zstd+dict setting
bl = blocks(64); samples = bl[::max(1, len(bl)//4000)][:4000]
zd = zstd.train_dictionary(110_000, samples); cz = zstd.ZstdCompressor(level=19, dict_data=zd)
comp = [cz.compress(b) for b in bl]; dz = zstd.ZstdDecompressor(dict_data=zd)
t0 = time.perf_counter(); [dz.decompress(x) for x in comp[:5000]]; dt = (time.perf_counter() - t0) / 5000 * 1e6
print("zstd+dict(64) decode: %.1f us/block (python, host)" % dt, " avg raw block %.0f B comp %.0f B" % (raw / len(bl), sum(map(len, comp)) / len(comp)))
