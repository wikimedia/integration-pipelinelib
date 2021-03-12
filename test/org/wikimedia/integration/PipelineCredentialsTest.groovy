import static groovy.test.GroovyAssert.*
import groovy.util.GroovyTestCase

import org.wikimedia.integration.PipelineCredentialManager

class PipelineCredentialsTest extends GroovyTestCase {

  void testConstructor_allowedCredentials() {
    def credentialManager = new PipelineCredentialManager([allowedCredentials: [foo: "StringBinding"]])

    assert credentialManager.allowedCredentials == [foo: "StringBinding"]
  }

  void testGenerateCredential_notAllowed() {
    def credentialManager = new PipelineCredentialManager([:])

    String message = shouldFail(RuntimeException) {
      credentialManager.generateCredential([id: "foo", name: "bar"])
    }

    assert message == "java.lang.RuntimeException: Invalid Credential: 'foo'. Allowed credentials: [:]"
  }

  void testGenerateCredential_notSupported() {
    def credentialManager = new PipelineCredentialManager([allowedCredentials: [foo: "NonSupportedBinding"]])

    String message = shouldFail(RuntimeException) {
      credentialManager.generateCredential([id: "foo", name: "bar"])
    }

    assert message == "java.lang.RuntimeException: Invalid Credential Binding: 'NonSupportedBinding'. Supported bindings: [StringBinding, UsernamePasswordMultiBinding, SSHUserPrivateKeyBinding]"
  }

  void testGenerateCredentials() {
    def credentialManager = new PipelineCredentialManager([allowedCredentials: [foo: "StringBinding", bar: "UsernamePasswordMultiBinding", baz: "SSHUserPrivateKeyBinding"]])

    def stringCredential = credentialManager.generateCredential([id: "foo", name: "bar"])
    assert stringCredential.id == "foo"
    assert stringCredential.name == "bar"

    def usernamePasswordCredential = credentialManager.generateCredential([id: "bar", usernameVariable: "bar", passwordVariable: "baz"])
    assert usernamePasswordCredential.id == "bar"
    assert usernamePasswordCredential.usernameVariable == "bar"
    assert usernamePasswordCredential.passwordVariable == "baz"

    def sshKeyCredential = credentialManager.generateCredential([id: "baz", keyFileVariable: "bar"])
    assert sshKeyCredential.id == "baz"
    assert sshKeyCredential.keyFileVariable == "bar"
  }

  void testCredentials_getBinding() {
    def credentialManager = new PipelineCredentialManager([allowedCredentials: [foo: "StringBinding", bar: "UsernamePasswordMultiBinding", baz: "SSHUserPrivateKeyBinding"]])

    def stringCredential = credentialManager.generateCredential([id: "foo", name: "bar"])
    assert stringCredential.getBinding() == [ $class: 'StringBinding', credentialsId: "foo", variable: "bar" ]

    def usernamePasswordCredential = credentialManager.generateCredential([id: "bar", usernameVariable: "bar", passwordVariable: "baz"])
    assert usernamePasswordCredential.getBinding() == [$class: 'UsernamePasswordMultiBinding', credentialsId: "bar", usernameVariable: "bar", passwordVariable: "baz"]

    def sshKeyCredential = credentialManager.generateCredential([id: "baz", keyFileVariable: "bar"])
    assert sshKeyCredential.getBinding() == [$class: 'SSHUserPrivateKeyBinding', credentialsId: "baz", keyFileVariable: "bar" ]
  }

    void testCredentials_getDockerEnvVars() {
    def credentialManager = new PipelineCredentialManager([allowedCredentials: [foo: "StringBinding", bar: "UsernamePasswordMultiBinding", baz: "SSHUserPrivateKeyBinding"]])

    def stringCredential = credentialManager.generateCredential([id: "foo", name: "bar"])
    assert stringCredential.getDockerEnvVars() ==  [ bar: '\${bar}' ]

    def usernamePasswordCredential = credentialManager.generateCredential([id: "bar", usernameVariable: "bar", passwordVariable: "baz"])
    assert usernamePasswordCredential.getDockerEnvVars() ==  [ bar: '\${bar}', baz: '\${baz}' ]

    def sshKeyCredential = credentialManager.generateCredential([id: "baz", keyFileVariable: "bar"])
    assert sshKeyCredential.getDockerEnvVars() ==  [ bar: '\${bar}' ]
  }
}