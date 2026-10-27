//! Codex desktop uses its own per-turn feedback database.
use super::{diagnostic_store, Adapter};
use std::path::Path;

pub(super) struct Source(diagnostic_store::Reader);
impl Source {
    pub(super) fn open(home: &Path) -> Self {
        Self(diagnostic_store::Reader::open(home))
    }
}
impl super::Source for Source {
    fn available(&self) -> bool {
        self.0.available()
    }
    fn file(&self, path: &Path) -> Box<dyn Adapter + '_> {
        Box::new(self.0.file(path))
    }
}
