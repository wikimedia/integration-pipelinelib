package org.wikimedia.integration

class PipelineCredentialManager implements Serializable {
  /**
   * The binding types currently supported by the PipelineLib
   */
  def supportedBindings = [
    'StringBinding',
    'UsernamePasswordMultiBinding',
    'SSHUserPrivateKeyBinding',
  ]

  /**
   * The allowed credentials for the current pipeline
   */
  def allowedCredentials = [:]

  /**
   * Returns a credential class based on credential object
   *
   * @param credentialObj an object with the credentialId and
   *                      properties to pass as environment
   *                      variables
   *
   * @return JenkinsCredential the created credential
   */
  JenkinsCredential generateCredential(Map credentialObj) {
    def allowedCredBinding = allowedCredentials.get(credentialObj.id)
    def cred

    if (!allowedCredBinding) {
      throw new RuntimeException("Invalid Credential: '${credentialObj.id}'. Allowed credentials: ${allowedCredentials.toMapString()}")
    }

    switch(allowedCredBinding) {
      case 'StringBinding':
        cred = new StringCredential(credentialObj)
        break
      case 'UsernamePasswordMultiBinding':
        cred = new UsernamePasswordCredential(credentialObj)
        break
      case 'SSHUserPrivateKeyBinding':
        cred = new SSHKeyCredential(credentialObj)
        break
      default:
        throw new RuntimeException("Invalid Credential Binding: '${allowedCredBinding}'. Supported bindings: ${supportedBindings}")
        break
    }

    return cred
  }
}

/**
 * A class for a credential
 */
abstract class JenkinsCredential implements Serializable {
  /**
   * The Jenkins credential id
   */
  String id

  /**
   * Returns a map of the credential bindings
   */
  abstract Map getBinding()

  /**
   * Returns a map of the environment variables in which to store
   * the credential values, for use in the docker run command
   */
  abstract Map getDockerEnvVars()
}

/**
 * A text credential
 */
class StringCredential extends JenkinsCredential {
  String name

  Map getBinding() {
    [ $class: 'StringBinding', credentialsId: id, variable: name ]
  }

  Map getDockerEnvVars() {
    [ (name): '\${' + name + '}' ]
  }
}

/**
 * A username and password credential
 */
class UsernamePasswordCredential extends JenkinsCredential {
  String usernameVariable
  String passwordVariable

  Map getBinding() {
    [
      $class: 'UsernamePasswordMultiBinding',
      credentialsId: id,
      usernameVariable: usernameVariable,
      passwordVariable: passwordVariable,
    ]
  }

  Map getDockerEnvVars() {
    [
      (usernameVariable): '\${' + usernameVariable + '}',
      (passwordVariable): '\${' + passwordVariable + '}',
    ]
  }
}

/**
 * An SSH key credential
 */
class SSHKeyCredential extends JenkinsCredential {
  String keyFileVariable

  Map getBinding() {
    [
      $class: 'SSHUserPrivateKeyBinding',
      credentialsId: id,
      keyFileVariable: keyFileVariable,
    ]
  }

  Map getDockerEnvVars() {
    [ (keyFileVariable): '\${' + keyFileVariable + '}' ]
  }
}