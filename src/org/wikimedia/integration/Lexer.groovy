package org.wikimedia.integration

import com.cloudbees.groovy.cps.NonCPS
import org.codehaus.groovy.GroovyException

/**
 * A lexical scanner for processing variable syntax in strings.
 */
class Lexer implements Serializable {
  final VAR_START   = '${'
  final VAR_END     = '}'
  final VAR_DEFAULT = '|'
  final VAR_NAME    = [
    'a', 'b', 'c', 'd', 'e', 'f', 'g', 'h', 'i', 'j', 'k', 'l', 'm',
    'n', 'o', 'p', 'q', 'r', 's', 't', 'u', 'v', 'w', 'x', 'y', 'z',
    'A', 'B', 'C', 'D', 'E', 'F', 'G', 'H', 'I', 'J', 'K', 'L', 'M',
    'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z',
    '0', '1', '2', '3', '4', '5', '6', '7', '8', '9',
    '.', '_', '-'
  ] as Set

  /**
   * Lexes the given string for valid syntax and returns a List of
   * {@link Literal} and {@link Variable} tokens.
   *
   * @example
   * <pre><code>
   *   def tokens = (new Lexer()).tokenize("a ${foo|with default} string.")
   *
   *   assert tokens.length() == 3
   *
   *   assert tokens[0] instanceof Lexer.Literal
   *   assert tokens[0].value == "a "
   *
   *   assert tokens[1] instanceof Lexer.Variable
   *   assert tokens[1].name == "foo"
   *   assert tokens[1].hasDefault
   *   assert tokens[1].defaultValue == "with default"
   *
   *   assert tokens[2] instanceof Lexer.Literal
   *   assert tokens[2].value == " string."
   * </code></pre>
   */
  List tokenize(String source) {
    def tokens = []

    while (source.length() > 0) {
      def nextVarPos = source.indexOf(VAR_START)

      if (nextVarPos < 0) {
        break
      }

      if (nextVarPos > 0) {
        tokens.add(new Literal(source.substring(0, nextVarPos)))
      }

      source = source.substring(nextVarPos)

      def variable = new Variable()
      def length = variable.scan(source)
      tokens.add(variable)

      source = source.substring(length)
    }

    if (source.length() > 0) {
      tokens.add(new Literal(source))
    }

    return tokens
  }

  class InvalidVariableException extends GroovyException {
    String source
    String reason

    @NonCPS
    String getMessage() {
      def msg = 'invalid variable expression'

      if (reason.length() > 0) {
        msg += ' (' + reason + ')'
      }

      msg + ': ' + source
    }
  }

  /**
   * Base class from which literal and variable tokens are derived.
   */
  class Token implements Serializable {
    String source = ''

    String toString() {
      source
    }
  }

  /**
   * Token representing a literal substring within the source string.
   */
  class Literal extends Token {
    String value

    Literal(String val) {
      source = val
      value = val
    }
  }

  /**
   * Token representing a variable within the source string.
   */
  class Variable extends Token {
    String name = ''
    String defaultValue = ''
    Boolean hasDefault = false

    /**
     * Scans the start of the given string for a valid variable expression. If
     * one is found, the variable name and default value are parsed into the
     * variable instance. If a variable expression is invalid, an exception is
     * thrown.
     */
    int scan(String src) throws InvalidVariableException {
      source = src

      def startPos = src.indexOf(VAR_START)

      // Quick sanity check for proper termination and length sanity check
      if (startPos != 0) {
        throw new InvalidVariableException(
          source: source,
          reason: 'missing the start delimiter ' + VAR_START
        )
      }

      def endPos = src.indexOf(VAR_END)

      if (endPos < 0) {
        throw new InvalidVariableException(
          source: source,
          reason: 'missing the end delimiter ' + VAR_END
        )
      }

      source = src.substring(0, endPos + 1)
      def body = src.substring(VAR_START.length(), endPos)

      name = ''

      for (def i = 0; i < body.length(); i++) {
        if (!VAR_NAME.contains(body[i])) {
          break
        }

        name += body[i]
      }

      if (name.length() == 0) {
        throw new InvalidVariableException(
          source: source,
          reason: 'name is missing'
        )
      }

      def remainder = body.substring(name.length())

      // Next character can be default delimiter or nothing
      if (remainder.length() > 0) {
        if (remainder[0] != VAR_DEFAULT) {
          throw new InvalidVariableException(
            source: source,
            reason: 'invalid character following name'
          )
        }

        hasDefault = true
        defaultValue = remainder.substring(1)
      }

      return source.length()
    }
  }
}
