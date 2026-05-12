#!/bin/bash
# Advanced Kotlin to Java-like filter for Doxygen 1.9.8

# 1. First, handle the signatures: fun name(p1: T1, p2: T2): RetType
# This is hard with sed, so we do it in steps.

cat "$1" | \
    # Remove annotations
    sed -E 's/@[a-zA-Z0-9_]+(\(.*\))?//g' | \
    # Remove companion object
    sed -E 's/companion object/static class Companion/g' | \
    # Remove suspend, internal, etc.
    sed -E 's/suspend|internal|override|open|abstract|sealed|data//g' | \
    # Handle class definitions with primary constructors
    sed -E 's/class[[:space:]]+([a-zA-Z0-9_]+)[[:space:]]*\(.*\)/class \1/g' | \
    # Handle function signatures (simplified)
    # Convert 'fun name(args): Type' to 'Type name(args)'
    sed -E 's/fun[[:space:]]+([a-zA-Z0-9_]+)\((.*)\)[[:space:]]*:[[:space:]]*([a-zA-Z0-9_.]+)/\3 \1(\2)/g' | \
    # For functions without explicit return type, use void
    sed -E 's/fun[[:space:]]+/void /g' | \
    # Handle parameters: 'name: Type' -> 'Type name'
    # We do this multiple times for multiple params
    sed -E 's/\(([a-zA-Z0-9_]+)[[:space:]]*:[[:space:]]*([a-zA-Z0-9_.<>?]+)/\(\2 \1/g' | \
    sed -E 's/,[[:space:]]*([a-zA-Z0-9_]+)[[:space:]]*:[[:space:]]*([a-zA-Z0-9_.<>?]+)/, \2 \1/g' | \
    sed -E 's/,[[:space:]]*([a-zA-Z0-9_]+)[[:space:]]*:[[:space:]]*([a-zA-Z0-9_.<>?]+)/, \2 \1/g' | \
    sed -E 's/,[[:space:]]*([a-zA-Z0-9_]+)[[:space:]]*:[[:space:]]*([a-zA-Z0-9_.<>?]+)/, \2 \1/g' | \
    # Handle val/var properties
    sed -E 's/(val|var)[[:space:]]+([a-zA-Z0-9_]+)[[:space:]]*:[[:space:]]*([a-zA-Z0-9_.<>?]+)/public \3 \2/g' | \
    sed -E 's/(val|var)[[:space:]]+/public /g'
