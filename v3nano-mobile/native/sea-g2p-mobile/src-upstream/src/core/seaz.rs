//! SEAZ: vi-only, block-compressed, random-access phoneme dictionary (feature `seaz`).
//! Format is documented in `tools/seap_to_seaz.py`. Lossless for merged + common.

use std::borrow::Cow;
use std::collections::HashMap;
use std::io;
use std::sync::{Arc, Mutex};

const HEADER: usize = 48;
const CACHE_BLOCKS: usize = 512;

struct IndexEntry {
    first_key: Vec<u8>,
    off: usize,
    comp_len: usize,
    raw_len: usize,
}

struct Table {
    entries: Vec<IndexEntry>,
    fields: usize, // strings per row after the key: 1 (merged) or 2 (common)
}

struct Inner {
    decoder: zstd::bulk::Decompressor<'static>,
    cache: HashMap<(u8, usize), Arc<Vec<u8>>>,
}

pub struct SeazIndex {
    merged: Table,
    common: Table,
    inner: Mutex<Inner>,
}

fn u32_at(b: &[u8], p: usize) -> io::Result<u32> {
    b.get(p..p + 4)
        .map(|s| u32::from_le_bytes(s.try_into().unwrap()))
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Truncated SEAZ"))
}

fn read_table(b: &[u8], mut pos: usize, nblocks: usize, fields: usize) -> io::Result<Table> {
    let mut entries = Vec::with_capacity(nblocks);
    for _ in 0..nblocks {
        let klen = u32_at(b, pos)? as usize;
        let key = b
            .get(pos + 4..pos + 4 + klen)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Truncated SEAZ index"))?
            .to_vec();
        let p = pos + 4 + klen;
        entries.push(IndexEntry {
            first_key: key,
            off: u32_at(b, p)? as usize,
            comp_len: u32_at(b, p + 4)? as usize,
            raw_len: u32_at(b, p + 8)? as usize,
        });
        pos = p + 12;
    }
    Ok(Table { entries, fields })
}

impl SeazIndex {
    pub fn parse(b: &[u8]) -> io::Result<Self> {
        if b.len() < HEADER {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "Truncated SEAZ header"));
        }
        let zd_off = u32_at(b, 20)? as usize;
        let zd_len = u32_at(b, 24)? as usize;
        let m_idx = u32_at(b, 28)? as usize;
        let m_n = u32_at(b, 32)? as usize;
        let c_idx = u32_at(b, 36)? as usize;
        let c_n = u32_at(b, 40)? as usize;
        let zdict = b
            .get(zd_off..zd_off + zd_len)
            .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "Bad SEAZ dict range"))?;
        let decoder = zstd::bulk::Decompressor::with_dictionary(zdict)?;
        Ok(Self {
            merged: read_table(b, m_idx, m_n, 1)?,
            common: read_table(b, c_idx, c_n, 2)?,
            inner: Mutex::new(Inner { decoder, cache: HashMap::new() }),
        })
    }

    fn block(&self, data: &[u8], which: u8, t: &Table, bi: usize) -> Option<Arc<Vec<u8>>> {
        let mut g = self.inner.lock().ok()?;
        if let Some(b) = g.cache.get(&(which, bi)) {
            return Some(b.clone());
        }
        let e = &t.entries[bi];
        let comp = data.get(e.off..e.off + e.comp_len)?;
        let raw = g.decoder.decompress(comp, e.raw_len).ok()?;
        if g.cache.len() >= CACHE_BLOCKS {
            g.cache.clear();
        }
        let arc = Arc::new(raw);
        g.cache.insert((which, bi), arc.clone());
        Some(arc)
    }

    fn find(&self, data: &[u8], which: u8, t: &Table, word: &str) -> Option<Vec<String>> {
        let w = word.as_bytes();
        let bi = t.entries.partition_point(|e| e.first_key.as_slice() <= w);
        if bi == 0 {
            return None;
        }
        let blk = self.block(data, which, t, bi - 1)?;
        let mut it = blk.split(|&c| c == 0);
        loop {
            let key = it.next()?;
            if key.is_empty() && it.clone().next().is_none() {
                return None;
            }
            let mut vals = Vec::with_capacity(t.fields);
            for _ in 0..t.fields {
                vals.push(String::from_utf8(it.next()?.to_vec()).ok()?);
            }
            if key == w {
                return Some(vals);
            }
        }
    }

    pub fn lookup_merged<'a>(&self, data: &[u8], word: &str) -> Option<Cow<'a, str>> {
        self.find(data, 0, &self.merged, word).map(|mut v| Cow::Owned(v.remove(0)))
    }

    pub fn lookup_common<'a>(&self, data: &[u8], word: &str) -> Option<(Cow<'a, str>, Cow<'a, str>)> {
        self.find(data, 1, &self.common, word).map(|mut v| {
            let e = v.remove(1);
            (Cow::Owned(v.remove(0)), Cow::Owned(e))
        })
    }
}
