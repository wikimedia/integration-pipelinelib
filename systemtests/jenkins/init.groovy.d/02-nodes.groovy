import jenkins.model.Jenkins

Jenkins.instance.computers.each { c ->
  c.node.labelString += "pipelinelib blubber dockerPublish"
}

Jenkins.instance.save()
