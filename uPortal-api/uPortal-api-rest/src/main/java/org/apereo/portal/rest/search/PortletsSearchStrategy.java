/**
 * Licensed to Apereo under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright ownership. Apereo
 * licenses this file to you under the Apache License, Version 2.0 (the "License"); you may not use
 * this file except in compliance with the License. You may obtain a copy of the License at the
 * following location:
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apereo.portal.rest.search;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apereo.portal.index.SearchField;
import org.apereo.portal.portlet.om.IPortletDefinition;
import org.apereo.portal.portlet.registry.IPortletDefinitionRegistry;
import org.apereo.portal.portlets.search.PortletRegistryUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Provides search results to the {@link SearchRESTController} that are portlets.
 *
 * @since 5.0
 */
@Component
public class PortletsSearchStrategy implements ISearchStrategy {

    private static final String RESULT_TYPE_NAME = "portlets";

    private MultiFieldQueryParser queryParser;

    @Autowired private Directory directory;

    @Autowired private IPortletDefinitionRegistry portletDefinitionRegistry;

    @Autowired private PortletRegistryUtil portletRegistryUtil;

    private final Logger logger = LoggerFactory.getLogger(getClass());

    @PostConstruct
    public void init() {
        final String[] fields =
                Arrays.stream(SearchField.values())
                        .map(SearchField::getValue)
                        .toArray(String[]::new);
        queryParser = new MultiFieldQueryParser(fields, new StandardAnalyzer());
    }

    @Override
    public String getResultTypeName() {
        return RESULT_TYPE_NAME;
    }

    @Override
    public List<?> search(String query, HttpServletRequest request) {

        final List<Object> rslt = new ArrayList<>();
        final Set<IPortletDefinition> seen = new HashSet<>();

        try (IndexReader indexReader = DirectoryReader.open(directory)) {

            final Query q = queryParser.parse(query);
            final IndexSearcher searcher = new IndexSearcher(indexReader);
            final TopDocs topDocs = searcher.search(q, 50);
            Arrays.stream(topDocs.scoreDocs)
                    .forEach(
                            scoreDoc -> {
                                try {
                                    final Document document = searcher.doc(scoreDoc.doc);
                                    final IPortletDefinition portlet =
                                            portletDefinitionRegistry.getPortletDefinitionByFname(
                                                    document.get(SearchField.FNAME.getValue()));
                                    if (seen.contains(portlet)) {
                                        // Don't process a portlet more than once...
                                        return;
                                    }
                                    seen.add(portlet);
                                    logger.debug(
                                            "Search query '{}' matches portlet: {}",
                                            query,
                                            portlet);
                                    /* requester permissions checked in buildPortletUrl() */
                                    final String url =
                                            portletRegistryUtil.buildPortletUrl(request, portlet);
                                    if (url != null) {
                                        logger.debug(
                                                "Adding portlet with fname='{}' to search results for query='{}'",
                                                portlet.getFName(),
                                                query);
                                        rslt.add(getPortletAttrs(portlet, url));
                                    }
                                } catch (IOException e) {
                                    // Log a warning, but don't prevent other matches from
                                    // succeeding...
                                    logger.warn(
                                            "Failed to process the following search result for query='{}': {}",
                                            query,
                                            scoreDoc,
                                            e);
                                }
                            });

        } catch (Exception e) {
            // Log a warning, but don't prevent other search strategies from succeeding...
            logger.warn("Failed to search portal content for query='{}'", query, e);
        }

        return rslt;
    }

    private Map<String, String> getPortletAttrs(IPortletDefinition portlet, String url) {
        final Map<String, String> rslt = new TreeMap<>();
        rslt.put("name", portlet.getName());
        rslt.put("fname", portlet.getFName());
        rslt.put("title", portlet.getTitle());
        rslt.put("description", portlet.getDescription());
        rslt.put("url", url);
        return rslt;
    }
}
