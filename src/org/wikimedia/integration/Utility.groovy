package org.wikimedia.integration

/**
 * Static functions of global utility plopped into a class.
 */
class Utility {
  /**
   * Quotes the given shell argument.
   *
   * @param argument Shell argument.
   * @return Quoted shell argument.
   */
  static String arg(String argument) {
    "'" + argument.replace("'", "'\\''") + "'"
  }
}
