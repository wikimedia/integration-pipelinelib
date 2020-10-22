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

  private List generateRemoteConfigs(credentialsID) {
    if (credentialsID) {
      return [[
        url: remote.toString(),
        refspec: ref,
        credentialsId: credentialsID
      ]]
    }

    return [[
      url: remote.toString(),
      refspec: ref,
    ]]
  }

  /**
   * Returns an SCM mapping that the Jenkins `checkout` function can use to
   * clone the project repo and check out the patch set.
   *
   * @param options Various checkout options.
   * <dl>
   * <dt><code>credentialsID</code></dt>
   * <dd>ID of a Jenkins credentials to supply to the remote server for remote
   * operations. Default is not to supply credentials.</dd>
   *
   * <dt><code>depth</code></dt>
   * <dd>Depth of the desired history when shallow is true. Default is
   * 20.</dd>
   *
   * <dt><code>shallow</code></dt>
   * <dd>Whether to do a shallow clone of the repo and its submodules.
   * Defaults to <code>true</code>.</dd>
   *
   * <dt><code>submodules</code></dt>
   * <dd>Whether to (recursively) update submodules after checkout. Defaults
   * to <code>true</code>.</dd>
   *
   * <dt><code>target</code></dt>
   * <dd>Directory in which to clone and checkout the working directory.
   * Defaults to the current directory.</dd>
   * </dl>
   */
  Map getSCM(Map options = [:]) {
    def depth = options.get('depth', 20)
    def shallow = options.get('shallow', true)

    def extensions = [
      [$class: 'WipeWorkspace'],
      [$class: 'CloneOption', depth: depth, shallow: shallow],
    ]

    if (options.get('submodules', true)) {
      extensions.add(
        [
          $class: 'SubmoduleOption',
          depth: depth,
          shallow: shallow,
          recursiveSubmodules: true,
        ]
      )
    } else {
      extensions.add(
        [
          $class: 'SubmoduleOption',
          disableSubmodules: true,
        ]
      )
    }

    if (options.target) {
      extensions.add(
        [
          $class: 'RelativeTargetDirectory',
          relativeTargetDir: options.target + '/'
        ]
      )
    }

    return [
      $class: 'GitSCM',
      userRemoteConfigs: generateRemoteConfigs(options.credentialsID),
      branches: [[
        name: commit,
      ]],
      extensions: extensions
    ]
  }
}
