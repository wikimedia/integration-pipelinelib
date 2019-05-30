import jenkins.model.Jenkins

Jenkins.instance.computers.each { c ->
  c.node.labelString += "blubber dockerPublish"
}

Jenkins.instance.save()
