package org.wikimedia.integration

import java.net.URI

/**
 * Provides an interface to a patch set being gated by WMF CI.
 *
 * {@code
 * import org.wikimedia.integration.PatchSet
 *
 * stage('Checkout patch') { checkout(PatchSet.fromZuul(params).getSCM())) }
 * }
 */
class PatchSet implements Serializable {
  /**
   * Git SHA-1 value representing this patch set in the remote.
   */
  String commit

  /**
   * Project name.
   */
  String project

  /**
   * Git ref that points to patch set commit.
   */
  String ref

  /**
   * Git remote location where the patch set can be fetched from.
   */
  URI remote

  /**
   * Constructs a new PatchSet from the given parameters set by Zuul during CI
   * gating: ZUUL_PROJECT, ZUUL_URL, ZUUL_REF, and ZUUL_COMMIT.
   *
   * @params params Parameters set by Zuul during CI gating.
   */
  static PatchSet fromZuul(Map params) {
    new PatchSet(
      commit: params.ZUUL_COMMIT,
      project: params.ZUUL_PROJECT,
      ref: params.ZUUL_REF,
      remote: URI.create(params.ZUUL_URL + "/").resolve(params.ZUUL_PROJECT)
    )
  }

  /**
   * Returns an SCM mapping that the Jenkins `checkout` function can use to
   * clone the project repo and check out the patch set.
   */
  Map getSCM() {
    [
      $class: 'GitSCM',
      userRemoteConfigs: [[
        url: remote.toString(),
        refspec: ref,
      ]],
      branches: [[
        name: commit,
      ]],
      extensions: [
        [$class: 'WipeWorkspace'],
        [$class: 'CloneOption', shallow: true],
        [$class: 'SubmoduleOption', recursiveSubmodules: true],
      ]
    ]
  }
}
