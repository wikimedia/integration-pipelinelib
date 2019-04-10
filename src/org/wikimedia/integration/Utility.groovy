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
  static String arg(String argument) {
    "'" + argument.replace("'", "'\\''") + "'"
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
    new Date().format("yyyy-MM-dd-HH-mmss", TimeZone.getTimeZone("UTC"))
  }
}
