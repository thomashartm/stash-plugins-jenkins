/*
 * Copyright [2016] [Thomas Hartmann]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package biz.netcentric.bitbucket.plugins.jenkins;

import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.atlassian.bitbucket.hook.repository.PostRepositoryHook;
import com.atlassian.bitbucket.hook.repository.PostRepositoryHookContext;
import com.atlassian.bitbucket.hook.repository.RepositoryPushHookRequest;
import com.atlassian.bitbucket.repository.RepositoryCloneLinksRequest;
import com.atlassian.bitbucket.repository.RepositoryService;
import com.atlassian.bitbucket.scope.Scope;
import com.atlassian.bitbucket.setting.Settings;
import com.atlassian.bitbucket.setting.SettingsValidationErrors;
import com.atlassian.bitbucket.setting.SettingsValidator;
import com.atlassian.bitbucket.util.NamedLink;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.pluginsettings.PluginSettingsFactory;
import com.atlassian.sal.api.transaction.TransactionCallback;
import com.atlassian.sal.api.transaction.TransactionTemplate;

/**
 * Asynchronous post receive hook that react's whenever a persistant operation has been applied to the repository. It uses the configurd
 * jenkins URL, created a notification URL fpor this repo and calls the URL. This triggers the jenkins build for the repository the jenkins
 * build trigger is configured.
 * 
 * @author Stephan.Kleine
 * @author Konrad Windszus
 * @since 04/2013
 */
@Component
public class JenkinsBuildTrigger implements PostRepositoryHook<RepositoryPushHookRequest>, SettingsValidator {
    private static final Logger LOG = LoggerFactory.getLogger(JenkinsBuildTrigger.class);

    private static final String PROPERTY_URL = "url";

    private final PluginSettingsFactory pluginSettingsFactory;
    private final TransactionTemplate transactionTemplate;
    private final RepositoryService repositoryService;

    @Autowired
    public JenkinsBuildTrigger(@ComponentImport final PluginSettingsFactory pluginSettingsFactory,
            @ComponentImport final TransactionTemplate transactionTemplate,
            @ComponentImport final RepositoryService repositoryService) {
        this.pluginSettingsFactory = pluginSettingsFactory;
        this.transactionTemplate = transactionTemplate;
        this.repositoryService = repositoryService;
    }

    
    /**
     * Notifies Jenkins of a new commit assuming Jenkins is configured to connect to Stash via SSH.
     */
    public void postUpdate(final PostRepositoryHookContext context, final RepositoryPushHookRequest request) {
        String url = context.getSettings().getString(PROPERTY_URL);
        if (StringUtils.isBlank(url)) {
            url = transactionTemplate.execute(new TransactionCallback<String>() {
                public String doInTransaction() {
                    return (String)pluginSettingsFactory.createGlobalSettings().get(ConfigResource.PLUGIN_KEY_URL);
                }
            });
        }

        try {
            final RepositoryCloneLinksRequest linksRequest = new RepositoryCloneLinksRequest.Builder()
                    .protocol("ssh")
                    .repository(request.getRepository())
                    .build();
            final Set<NamedLink> links = repositoryService.getCloneLinks(linksRequest);
            if (links.isEmpty()) {
                LOG.error("Unable to calculate clone link for repository [{}]", request.getRepository());
            } else {
                url = String.format("%s/git/notifyCommit?url=%s", url, URLEncoder.encode(links.iterator().next().getHref(), "UTF-8"));
            }
        } catch (UnsupportedEncodingException e) {
            LOG.error(e.getMessage(), e);
        }

        LOG.debug("Notifying Jenkins via URL: [{}]", url);

        if (url != null) {
            try {
                new URL(url).openConnection().getInputStream().close();
            } catch (Exception e) {
                LOG.error("Unable to connect to Jenkins at [" + url + "]", e);
            }
        }
    }

    public void validate(final Settings settings, final SettingsValidationErrors errors, final Scope scope) {
        final String url = settings.getString(PROPERTY_URL, "");
        if (StringUtils.isNotEmpty(url)) {
            try {
                new URL(url);
            } catch (MalformedURLException e) {
                errors.addFieldError(PROPERTY_URL, "Please supply a valid URL");
            }
        }
    }
}