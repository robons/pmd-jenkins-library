def call() {
    configFileProvider([configFile(fileId: 'pmdConfig', variable: 'configfile')]) {
        def config = readJSON(text: readFile(file: configfile))
        String PMD = config['pmd_api']
        String credentials = config['credentials']
        def jobDraft = drafter.findDraftset(PMD, credentials, env.JOB_NAME)
        if (jobDraft) {
            drafter.publishDraftset(PMD, credentials, jobDraft.id)
        } else {
            error "Expecting a draftset for this job."
        }
    }
}
