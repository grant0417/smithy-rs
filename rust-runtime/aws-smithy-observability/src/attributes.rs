/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! Attributes (also referred to as tags or annotations in other telemetry systems) are structured
//! key-value pairs that annotate a span or event. Structured data allows observability backends
//! to index and process telemetry data in ways that simple log messages lack.

use std::collections::HashMap;

/// The valid types of values accepted by [Attributes].
#[non_exhaustive]
#[derive(Clone, Debug, PartialEq)]
pub enum AttributeValue {
    /// Holds an [i64]
    I64(i64),
    /// Holds an [f64]
    F64(f64),
    /// Holds a [String]
    String(String),
    /// Holds a [bool]
    Bool(bool),
}

/// Structured telemetry metadata.
#[non_exhaustive]
#[derive(Clone)]
pub struct Attributes {
    attrs: HashMap<String, AttributeValue>,
}

impl Attributes {
    /// Create a new empty instance of [Attributes].
    pub fn new() -> Self {
        Self {
            attrs: HashMap::new(),
        }
    }

    /// Set an attribute.
    pub fn set(&mut self, key: String, value: AttributeValue) {
        self.attrs.insert(key, value);
    }

    /// Get an attribute.
    pub fn get(&self, key: String) -> Option<&AttributeValue> {
        self.attrs.get(&key)
    }

    /// Get all of the attribute key value pairs.
    pub fn attributes(&self) -> &HashMap<String, AttributeValue> {
        &self.attrs
    }
}

impl Default for Attributes {
    fn default() -> Self {
        Self::new()
    }
}

/// Delineates a logical scope that has some beginning and end
/// (e.g. a function or block of code).
pub trait Scope {
    /// invoke when the scope has ended.
    fn end(&self);
}

/// A cross cutting concern for carrying execution-scoped values across API
/// boundaries (both in-process and distributed).
pub trait Context {
    /// Make this context the currently active context.
    /// The returned handle is used to return the previous
    /// context (if one existed) as active.
    fn make_current(&self) -> &dyn Scope;
}

/// Keeps track of the current [Context].
pub trait ContextManager {
    ///Get the currently active context.
    fn current(&self) -> &dyn Context;
}