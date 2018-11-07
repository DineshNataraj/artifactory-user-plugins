import org.jfrog.artifactory.client.Artifactory
import org.jfrog.artifactory.client.ArtifactoryClientBuilder
import org.jfrog.artifactory.client.impl.RepositoryHandleImpl
import org.jfrog.artifactory.client.model.Item
import org.jfrog.artifactory.client.model.repository.settings.impl.DebianRepositorySettingsImpl

import spock.lang.Specification

/*
 * Copyright (C) 2018 JFrog Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

class ExpireFilesMetadataTest extends Specification {

    static final baseUrl = 'http://localhost:8088/artifactory'
    static final remoteRepoKey = 'debian-remote'
    static final virtualRepoKey = 'debian-virtual'
    static final remoteRepoUrl = 'http://jfrog.bintray.com/artifactory-pro-debs/'
    static final packagesPath = 'dists/xenial/main/binary-i386/Packages.gz'

    def 'Files not expired download test'() {
        setup:
        def artifactory = ArtifactoryClientBuilder.create().setUrl(baseUrl)
                .setUsername('admin').setPassword('password').build()
        def remote = createRemoteDebianRepo(artifactory, remoteRepoKey)
        def virtual = createVirtualRepo(artifactory, virtualRepoKey, remoteRepoKey)

        artifactory.plugins().execute('expireFilesMetadataConfig').
                withParameter('action', 'reset').withParameter('repositories', '["debian-remote":[1800, ["**/*Packages.gz"]]]').sync()

        when:
        // Perform first download request
        def infoFirstDownloadRequest =  downloadAndGetInfo(remote, virtual, packagesPath)
        // Perform second download request
        def infoSecondDownloadRequest = downloadAndGetInfo(remote, virtual, packagesPath)

        then:
        /*
         * Having the same last updated time indicates that the file was not fetched
         * from the remote repository between download requests
         */
        infoSecondDownloadRequest.lastUpdated == infoFirstDownloadRequest.lastUpdated

        cleanup:
        remote?.delete()
        virtual?.delete()
    }

    def 'Files expired download test'() {
        setup:
        def artifactory = ArtifactoryClientBuilder.create().setUrl(baseUrl)
                .setUsername('admin').setPassword('password').build()
        def remote = createRemoteDebianRepo(artifactory, remoteRepoKey)
        def virtual = createVirtualRepo(artifactory, virtualRepoKey, remoteRepoKey)

        artifactory.plugins().execute('expireFilesMetadataConfig').
                withParameter('action', 'reset').withParameter('repositories', '["debian-remote":[1, ["**/*Packages.gz"]]]').sync()

        when:
        // Perform first download request
        def infoFirstDownloadRequest =  downloadAndGetInfo(remote, virtual, packagesPath)
        // wait 2 seconds so the cache can have time to expire
        sleep(2000l)
        // Perform second download request
        def infoSecondDownloadRequest = downloadAndGetInfo(remote, virtual, packagesPath)

        then:
        /*
         * Last updated time after the second download request must be bigger
         * than after the first download, indicating that the remote artifact was fetched again
         * after the cache time has expired
         */
        infoSecondDownloadRequest.lastUpdated > infoFirstDownloadRequest.lastUpdated

        cleanup:
        remote?.delete()
        virtual?.delete()
    }

    private RepositoryHandleImpl createRemoteDebianRepo(Artifactory artifactory, String key) {
        def remoteBuilder = artifactory.repositories().builders().remoteRepositoryBuilder()
                .key(key)
                .repositorySettings(new DebianRepositorySettingsImpl())
                .url(remoteRepoUrl)
        artifactory.repositories().create(0, remoteBuilder.build())
        return artifactory.repository(key)
    }

    private RepositoryHandleImpl createVirtualRepo(Artifactory artifactory, String key, String includedRepo) {
        def virtualBuilder = artifactory.repositories().builders().virtualRepositoryBuilder().key(key)
        virtualBuilder.repositories([includedRepo])
        artifactory.repositories().create(0, virtualBuilder.build())
        return artifactory.repository(key)
    }

    private Item downloadAndGetInfo(RepositoryHandleImpl remote, RepositoryHandleImpl virtual, String path) {
        remote.download(path).doDownload().text
        virtual.file(path).info()
    }
}
