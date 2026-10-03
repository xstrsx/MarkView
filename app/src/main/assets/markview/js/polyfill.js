/*!
 * MarkView — small feature shims.
 *
 * Everything here is a no-op on a current Android System WebView. It only exists so
 * that a device whose WebView happens to be older than the bundled renderers still
 * reaches the same code paths instead of dying with a ReferenceError.
 * Loaded before every vendor script (see index.html).
 */
(function (global) {
  "use strict";

  function define(target, name, value) {
    try {
      Object.defineProperty(target, name, {
        value: value,
        writable: true,
        configurable: true,
        enumerable: false,
      });
    } catch (error) {
      target[name] = value;
    }
  }

  if (!global.globalThis) {
    define(global, "globalThis", global);
  }

  if (!Object.hasOwn) {
    define(Object, "hasOwn", function (object, key) {
      return Object.prototype.hasOwnProperty.call(object, key);
    });
  }

  if (!Object.fromEntries) {
    define(Object, "fromEntries", function (entries) {
      var result = {};
      var list = entries || [];
      for (var i = 0; i < list.length; i++) {
        result[list[i][0]] = list[i][1];
      }
      return result;
    });
  }

  if (!Array.prototype.at) {
    define(Array.prototype, "at", function (index) {
      var length = this.length >>> 0;
      var position = Math.trunc(index) || 0;
      if (position < 0) position += length;
      if (position < 0 || position >= length) return undefined;
      return this[position];
    });
  }

  if (!String.prototype.at) {
    define(String.prototype, "at", function (index) {
      var value = String(this);
      var position = Math.trunc(index) || 0;
      if (position < 0) position += value.length;
      if (position < 0 || position >= value.length) return undefined;
      return value.charAt(position);
    });
  }

  if (!String.prototype.replaceAll) {
    define(String.prototype, "replaceAll", function (search, replacement) {
      if (search instanceof RegExp) {
        if (!search.global) {
          throw new TypeError("replaceAll must be called with a global RegExp");
        }
        return String(this).replace(search, replacement);
      }
      return String(this).split(String(search)).join(String(replacement));
    });
  }

  if (!Array.prototype.flat) {
    define(Array.prototype, "flat", function (depth) {
      var levels = depth === undefined ? 1 : Math.trunc(depth);
      return flatten(this, levels);
    });
    function flatten(list, depth) {
      var result = [];
      for (var i = 0; i < list.length; i++) {
        var value = list[i];
        if (Array.isArray(value) && depth > 0) {
          var nested = flatten(value, depth - 1);
          for (var j = 0; j < nested.length; j++) result.push(nested[j]);
        } else {
          result.push(value);
        }
      }
      return result;
    }
  }

  if (!Array.prototype.flatMap) {
    define(Array.prototype, "flatMap", function (callback, thisArg) {
      var mapped = [];
      for (var i = 0; i < this.length; i++) {
        if (i in this) mapped.push(callback.call(thisArg, this[i], i, this));
      }
      return mapped.flat();
    });
  }

  if (!Array.prototype.includes) {
    define(Array.prototype, "includes", function (search, fromIndex) {
      return this.indexOf(search, fromIndex || 0) !== -1;
    });
  }

  if (!Promise.allSettled) {
    define(Promise, "allSettled", function (iterable) {
      var list = Array.isArray(iterable) ? iterable : Array.from(iterable || []);
      return Promise.all(
        list.map(function (item) {
          return Promise.resolve(item).then(
            function (value) { return { status: "fulfilled", value: value }; },
            function (reason) { return { status: "rejected", reason: reason }; }
          );
        })
      );
    });
  }

  if (!Promise.any) {
    define(Promise, "any", function (iterable) {
      var list = Array.isArray(iterable) ? iterable : Array.from(iterable || []);
      return new Promise(function (resolve, reject) {
        var pending = list.length;
        var errors = [];
        if (pending === 0) {
          reject(new Error("All promises were rejected"));
          return;
        }
        list.forEach(function (item, index) {
          Promise.resolve(item).then(resolve, function (reason) {
            errors[index] = reason;
            pending -= 1;
            if (pending === 0) reject(new Error("All promises were rejected"));
          });
        });
      });
    });
  }

  if (!global.queueMicrotask) {
    define(global, "queueMicrotask", function (callback) {
      Promise.resolve().then(callback);
    });
  }

  if (!global.structuredClone) {
    define(global, "structuredClone", function (value) {
      if (value === undefined || value === null || typeof value !== "object") return value;
      try {
        return JSON.parse(JSON.stringify(value));
      } catch (error) {
        return value;
      }
    });
  }

  // Mermaid only needs word segmentation; the real implementation is unavailable on
  // older WebViews, and a whitespace split keeps its behaviour reasonable.
  if (!global.Intl) {
    define(global, "Intl", {});
  }
  if (global.Intl && !global.Intl.Segmenter) {
    define(global.Intl, "Segmenter", function Segmenter() {
      return {
        segment: function (text) {
          var value = String(text == null ? "" : text);
          var parts = value.split(/(\s+)/).filter(function (part) { return part.length > 0; });
          var position = 0;
          var index = 0;
          return {
            next: function () {
              if (index >= parts.length) return { done: true, value: undefined };
              var segment = parts[index++];
              var start = position;
              position += segment.length;
              return { done: false, value: { segment: segment, index: start, input: value } };
            },
            [Symbol.iterator]: function () { return this; },
          };
        },
      };
    });
  }
})(typeof window !== "undefined" ? window : this);
