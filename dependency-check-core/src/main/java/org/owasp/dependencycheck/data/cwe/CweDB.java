/*
 * This file is part of dependency-check-core.
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
 *
 * Copyright (c) 2012 Jeremy Long. All Rights Reserved.
 */
package org.owasp.dependencycheck.data.cwe;

import org.owasp.dependencycheck.utils.FileUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.util.HashMap;
import java.util.Map;

/**
 *
 * @author Jeremy Long
 */
public final class CweDB {

    /**
     * The Logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(CweDB.class);

    /**
     * Empty private constructor as this is a utility class.
     */
    private CweDB() {
        //empty
    }
    /**
     * A HashMap of the CWE data.
     */
    private static final Map<String, String> CWE = loadData();

    /**
     * Loads a HashMap containing the CWE data from a resource found in the jar.
     *
     * @return a HashMap of CWE data
     */
    private static Map<String, String> loadData() {
        final String filePath = "data/cwe.hashmap.serialized";
        try (InputStream input = FileUtils.getResourceAsStream(filePath);
                ObjectInputStream oin = new ObjectInputStream(input)) {

            final Map<String, String> ret = (HashMap<String, String>) oin.readObject();
            return ret;
        } catch (ClassNotFoundException ex) {
            LOGGER.warn("Unable to load CWE data. This should not be an issue.");
            LOGGER.debug("", ex);
        } catch (IOException ex) {
            LOGGER.warn("Unable to load CWE data due to an IO Error. This should not be an issue.");
            LOGGER.debug("", ex);
        }
        return null;
    }

    /**
     * <p>
     * Returns the full CWE name from the CWE ID.</p>
     *
     * @param cweId the CWE ID
     * @return the full name of the CWE
     */
    public static String getCweName(String cweId) {
        if (cweId != null) {
            return CWE.get(cweId);
        }
        return null;
    }
}
