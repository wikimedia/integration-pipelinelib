package org.wikimedia.integration

import com.cloudbees.groovy.cps.NonCPS

/**
 * Static functions of global utility plopped into a class.
 */
class Utility {
  /**
   * Official regular expression used to represent and capture parts of OCI
   * image references. The capture groups are: image_name, image_tag, digest.
   *
   * @see https://github.com/distribution/distribution/blob/main/reference/regexp.go
   */
  private final static OCI_IMAGE_REF = '''^((?:(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])(?:(?:\\.(?:[a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9]))+)?(?::[0-9]+)?/)?[a-z0-9]+(?:(?:(?:[._]|__|[-]*)[a-z0-9]+)+)?(?:(?:/[a-z0-9]+(?:(?:(?:[._]|__|[-]*)[a-z0-9]+)+)?)+)?)(?::([\\w][\\w.-]{0,127}))?(?:@([A-Za-z][A-Za-z0-9]*(?:[-_+.][A-Za-z][A-Za-z0-9]*)*[:][[:xdigit:]]{32,}))?$'''

  /**
   * Quotes the given shell argument.
   *
   * @param argument Shell argument.
   * @return Quoted shell argument.
   */
  static String arg(argument) {
    "'" + argument.toString().replace("'", "'\\''") + "'"
  }

  /**
   * Quotes all given shell arguments.
   *
   * @param arguments Shell argument.
   * @return Quoted shell arguments.
   */
  static String args(List arguments) {
    arguments.collect { arg(it) }.join(" ")
  }

  /**
   * Similar to collectNested, recursively iterates through the given object's
   * collections and transforms each value by calling the given closure. Where
   * this differs from the former is that even the collection values
   * themselves are transformed. This method also supports Maps whereas the
   * former does not.
   */
  static def collectAllNested(obj, func = { it }) {
    switch (obj) {
      case Map:
        obj = obj.collectEntries { k, v -> [k, collectAllNested(v, func)] }
        break;
      case Collection:
        obj = obj.collect { v -> collectAllNested(v, func) }
        break;
    }

    func(obj)
  }

  /**
   * Combines all given environment variables into docker option format
   * @param envVars Environment variables
   * @return environment variable options string
   */
  static String envs(Map envVars) {
    envVars ? envVars.collect { k, v ->
         '-e "' + k + '=' + v + '"'
    }.join(" ") + " " : ""
  }

  /**
   * Iterates recursively over lists and maps in the given object and returns
   * a new object where each member string has been mapped to a new value
   * using the given closure.
   *
   * {@code
   * mapStrings([foo: [bar: "ab"], baz: "xy"]) { it.reverse() }
   * // [foo: [bar: "ba"], baz: "yx"]
   * }
   */
  static def mapStrings(value, func = { it }) {
    switch (value) {
      case Map:
        return value.collectEntries { k, v -> [k, mapStrings(v, func)] }
      case Collection:
        return value.collect { v -> mapStrings(v, func) }
      case String:
      case GString:
        return func(value)
      default:
        return value
    }
  }

  /**
   * Merges two or more maps recursively.
   *
   * {@code
   * merge([foo: [bar: "x", baz: "y"]], [foo: [baz: "z"]])
   * // [foo: [bar: "x", baz: "z"]]
   * }
   */
  static Map merge(Map... maps) {
    if (maps.length == 0) {
      return [:]
    } else if (maps.length == 1) {
      return maps[0]
    }

    Map result = [:]

    maps.each { map ->
      map.each { k, v ->
        if (v instanceof Map && result[k] instanceof Map) {
          result[k] = merge(result[k], v)
        } else {
          result[k] = v
        }
      }
    }

    return result
  }

  /**
   * Parses the given OCI image ref into constituent parts: (name, tag,
   * digest). If the given string is an invalid ref pattern, null is returned.
   *
   * @param ref Full image ref string.
   */
  @NonCPS
  static Map parseImageRef(String ref) {
    def match = (ref =~ OCI_IMAGE_REF)

    if (!match) {
      return null
    }

    return [
      name: match[0][1],
      tag: match[0][2],
      digest: match[0][3],
    ]
  }

  /**
   * Returns a random alpha-numeric string that's length long.
   *
   * @param length Desired length of string.
   */
  @NonCPS
  static String randomAlphanum(length) {
    def alphanums = ('a'..'z') + ('0'..'9')
    def random = new Random()

    (1..length).collect { alphanums[random.nextInt(alphanums.size())] }.join()
  }

  /**
   * Forces the given path to be relative and absent any parent directory
   * links (<code>..</code>) that would resolve to a directory outside the
   * current one.
   *
   * @param path Path to sanitize.
   *
   * @return Sanitized path.
   */
  static String sanitizeRelativePath(String path) {
    def relativeURI = (new URI('/')).relativize(new URI(path)).normalize()

    // At this point, we're ensured a relative path and the inner part of the
    // path should have no '..' references. However, there may be one of more
    // leading '..' references which so simply remove them with a regex.
    relativeURI.getPath().replaceAll(/^(..\/)*/, '')
  }

  /**
   * Returns a timestamp suitable for use in image names, tags, etc.
   */
  static String timestampLabel() {
    new Date().format("yyyy-MM-dd-HHmmss", TimeZone.getTimeZone("UTC"))
  }
}
