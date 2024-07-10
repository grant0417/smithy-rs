use std::default::Default;
use std::path::Path;

use bytes::Bytes;

use crate::io::error::Error;
use crate::io::path_body::PathBody;
pub use crate::io::path_body::PathBodyBuilder;
use crate::types::SizeHint;

/// Source of binary data.
///
/// `InputStream` wraps a stream of data for ease of use.
#[derive(Debug)]
pub struct InputStream {
    pub(super) inner: RawInputStream,
}

impl InputStream {
    /// Create a new `InputStream` from a static byte slice
    pub fn from_static(bytes: &'static [u8]) -> Self {
        let inner = RawInputStream::Buf(bytes.into());
        Self { inner }
    }

    // FIXME - we really don't want size hint, we want streams with known content size I think or else we can't respect 10K max part size,
    //         I suppose we can make that an error for the time being and keep it open to unbounded streams if possible later...
    /// Return the bounds on the remaining length of the `InputStream`
    pub fn size_hint(&self) -> SizeHint {
        self.inner.size_hint()
    }

    /// Returns a [`PathBodyBuilder`], allowing you to build a `InputStream` with
    /// full control over how the file is read (eg. specifying the length of
    /// the file or the starting offset to read from).
    ///
    /// ```no_run
    /// # {
    /// use aws_s3_transfer_manager::io::InputStream;
    ///
    /// async fn input_stream_from_file() -> InputStream {
    ///     let stream = InputStream::read_from()
    ///         .path("docs/some-large-file.csv")
    ///         // Specify the length of the file used (skips an additional call to retrieve the size)
    ///         .length(123_456)
    ///         .build()
    ///         .expect("valid path");
    ///     stream
    /// }
    /// # }
    /// ```
    pub fn read_from() -> PathBodyBuilder {
        PathBodyBuilder::new()
    }

    /// Create a new `InputStream` that reads data from a given `path`.
    ///
    /// ## Warning
    /// The contents of the file MUST not change. The length & checksum of the file
    /// will be cached. If the contents of the file change, the operation will almost certainly fail.
    ///
    /// Furthermore, a partial write MAY seek in the file and resume from the previous location.
    ///
    /// # Examples
    /// ```no_run
    /// use aws_s3_transfer_manager::io::InputStream;
    /// use std::path::Path;
    ///  async fn make_stream() -> InputStream {
    ///     InputStream::from_path("docs/rows.csv").expect("file should be readable")
    /// }
    /// ```
    pub fn from_path(path: impl AsRef<Path>) -> Result<InputStream, Error> {
        Self::read_from().path(path).build()
    }
}

#[derive(Debug)]
pub(super) enum RawInputStream {
    /// In-memory buffer to read from
    Buf(Bytes),
    /// File based input
    Fs(PathBody),
}

impl RawInputStream {
    pub(super) fn size_hint(&self) -> SizeHint {
        // match self {
        //     Inner::Buf(bytes) => SizeHint::exact(bytes.remaining() as u64),
        //     // Inner::Fs(path) => SizeHint::exact(path.)
        //     // Inner::Dyn(st) => st.
        // }
        unimplemented!()
    }
}

impl Default for InputStream {
    fn default() -> Self {
        Self {
            inner: RawInputStream::Buf(Bytes::default()),
        }
    }
}

impl From<Bytes> for InputStream {
    fn from(value: Bytes) -> Self {
        Self {
            inner: RawInputStream::Buf(value),
        }
    }
}

impl From<Vec<u8>> for InputStream {
    fn from(value: Vec<u8>) -> Self {
        Self::from(Bytes::from(value))
    }
}
