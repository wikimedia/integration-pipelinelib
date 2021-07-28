import groovy.util.GroovyTestCase

import org.wikimedia.integration.Lexer

class LexerTestCase extends GroovyTestCase {
  void testVariableScan_validNoDefault() {
    def lexer = new Lexer()
    def var = new Lexer.Variable(lexer)
    def source = '${foo.bar} extra'

    def len = var.scan(source)

    assert len == 10
    assert var.source == '${foo.bar}'
    assert var.name == 'foo.bar'
    assert !var.hasDefault
  }

  void testVariableScan_validWithDefault() {
    def lexer = new Lexer()
    def var = new Lexer.Variable(lexer)
    def source = '${foo.bar|hi} extra'

    def len = var.scan(source)

    assert len == 13
    assert var.source == '${foo.bar|hi}'
    assert var.name == 'foo.bar'
    assert var.hasDefault
    assert var.defaultValue == 'hi'
  }


  void testLexer_Literal() {
    def lexer = new Lexer()
    def source = '''>Just a
                    >basic string.'''.stripMargin('> ')

    def tokens = lexer.tokenize(source)

    assert tokens.size() == 1
    assert tokens[0] instanceof Lexer.Literal
    assert tokens[0].value == source
  }

  void testLexer_Variable() {
    def lexer = new Lexer()
    def source = 'A string with ${a.variable} and another ${.variable|some default}.'

    def tokens = lexer.tokenize(source)

    assert tokens.size() == 5

    assert tokens[0] instanceof Lexer.Literal
    assert tokens[0].value == 'A string with '

    assert tokens[1] instanceof Lexer.Variable
    assert tokens[1].source == '${a.variable}'
    assert tokens[1].name == 'a.variable'
    assert tokens[1].hasDefault == false

    assert tokens[2] instanceof Lexer.Literal
    assert tokens[2].value == ' and another '

    assert tokens[3] instanceof Lexer.Variable
    assert tokens[3].source == '${.variable|some default}'
    assert tokens[3].name == '.variable'
    assert tokens[3].hasDefault == true
    assert tokens[3].defaultValue == 'some default'

    assert tokens[4] instanceof Lexer.Literal
    assert tokens[4].value == '.'
  }

  void testLexer_BareVariable() {
    def lexer = new Lexer()
    def source = '${.stage}'

    def tokens = lexer.tokenize(source)

    assert tokens.size() == 1

    assert tokens[0] instanceof Lexer.Variable
    assert tokens[0].source == '${.stage}'
    assert tokens[0].name == '.stage'
    assert tokens[0].hasDefault == false
  }

  void testLexer_PartialVariableSyntax() {
    def lexer = new Lexer()
    def source = 'A string with ${not.a.variable'

    shouldFail(Lexer.InvalidVariableException) {
      lexer.tokenize(source)
    }
  }

  void testLexer_InvalidVariableSyntax_BadName() {
    def lexer = new Lexer()
    def source = 'A string with an ${invalid.variable&name}.'

    shouldFail(Lexer.InvalidVariableException) {
      lexer.tokenize(source)
    }
  }

  void testLexer_InvalidVariableSyntax_MissingName() {
    def lexer = new Lexer()
    def source = '${}'

    shouldFail(Lexer.InvalidVariableException) {
      lexer.tokenize(source)
    }
  }
}
