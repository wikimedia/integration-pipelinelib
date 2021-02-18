package org.wikimedia.integration

/**
 * Static functions of global utility plopped into a class.
 */
class Utility {
  /**
   * Random number generator.
   */
  static private final Random random = new Random()

  /**
   * Alphabet used for random string generation.
   */
  static private final alphanums = ('a'..'z') + ('0'..'9')

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
      case List:
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
   * Returns a random alpha-numeric string that's length long.
   *
   * @param length Desired length of string.
   */
  static String randomAlphanum(length) {
    (1..length).collect { alphanums[random.nextInt(alphanums.size())] }.join()
  }

  /**
   * Returns a timestamp suitable for use in image names, tags, etc.
   */
  static String timestampLabel() {
    new Date().format("yyyy-MM-dd-HHmmss", TimeZone.getTimeZone("UTC"))
  }
}
