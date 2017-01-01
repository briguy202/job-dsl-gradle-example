import com.pipeline.jobs.utils.BaseJob
import com.pipeline.jobs.utils.CloudFormationBaseJob
import groovy.json.JsonSlurper

def json = new JsonSlurper()
def codeDir = "$WORKSPACE/$CODE_FOLDER"
String sourceDirName = "infrastructure/jobs"
def sourceDir = new File("$codeDir/$sourceDirName")
def scriptsDir = new File("$WORKSPACE/scripts")
def configuration = json.parseText(new File("${codeDir}/config/environments.json").text)

Map config = [
    project: configuration,
    folders: [
        source : sourceDirName,
        scripts: scriptsDir,
        code   : codeDir,
        working: new File("$WORKSPACE/.script-run")
    ],
    git    : [
        seedbranch : GIT_BRANCH,
        jobbranch  : GIT_JOB_BRANCH,
        credentials: GIT_CREDENTIALS
    ],
    common : [
        namespace: NAMESPACE,
        workspace: WORKSPACE
    ],
    scripts: [
        deployStackScript: new File("$scriptsDir/python/deploy_stacks.py").text,
        nodeScript       : new File("$scriptsDir/node/cloudformation.js").text
    ],
    dynamic: [:]
]

// Recursively look for groovy files in the destination source code
def filePattern = ~/.*\.groovy/
GroovyClassLoader cLoader = new GroovyClassLoader(this.class.getClassLoader())
def findFilenameClosure =
    {
        if (filePattern.matcher(it.name).find()) {
            println "Processing file ${it} ..."
            Class clazz = cLoader.parseClass(new File("$it"))
            def instance = clazz.newInstance()

            def createdFolders = []
            def recurse
            recurse = { Object node, String key, Map props ->
                if (key) {
                    props[key] = node
                    key = ''
                } else {
                    key = node.key
                }

                if (node.value instanceof Map && node.value.size() > 0) {
                    if (instance.metaClass.methods.find { it.name == "getExecute_${node.key}" }) {
                        println "Calling execute_${node.key}() ..."
                        node.value.each {
                            def keyVal = key
                            if (keyVal) {
                                props[keyVal] = it
                                keyVal = ''
                            } else {
                                keyVal = it.key
                            }

                            // Create the structure that will be used for folder names, stack names, and any
                            // other value that is created according to the path that is built.
                            config.jobfolders = [NAMESPACE]
                            config.jobfolders.addAll(props.collect { it.value.key })

                            if (instance.metaClass.methods.find { it.name == "getPreCreateFolders" }) {
                                instance.preCreateFolders(props, config)
                            }

                            config.jobfullpath = config.jobfolders.collect { it }.join('/')

                            // Build folder path for folders that haven't been created up to this point.
                            def buildPath = []
                            config.jobfolders.each {
                                buildPath.push(it)
                                def fold = buildPath.join('/')
                                if (!createdFolders.contains(fold)) {
                                    createdFolders.add(fold)
                                    println "Creating folder $fold"
                                    folder(fold)
                                }
                            }

                            def baseTypes = [
                                "base"          : BaseJob,
                                "cloudformation": CloudFormationBaseJob
                            ]

                            // Execute the dynamically-named method
                            instance."execute_${node.key}".delegate = this
                            instance."execute_${node.key}".resolveStrategy = Closure.DELEGATE_FIRST
                            instance."execute_${node.key}"(props, config, baseTypes)

                            props.remove(keyVal)
                        }
                    }

                    node.value.each {
                        recurse(it, key, props)
                    }
                }

                props.remove(key)
            }

            configuration.each {
                recurse(it, '', [:])
            }
        }
    }

if (sourceDir.exists()) {
    sourceDir.eachFileRecurse(findFilenameClosure)
} else {
    println "No build jobs exist at $sourceDir"
}