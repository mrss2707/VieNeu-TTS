//! Language-agnostic infrastructure shared by every language module:
//! the phoneme dictionary loader today; the generic abbreviation table and
//! pipeline scaffolding as they are extracted from the Vietnamese module.

pub mod abbrev;
pub mod dict;
pub mod numeric;
pub mod roman;
#[cfg(feature = "seaz")]
pub mod seaz;
pub mod spans;
pub mod units;
