/**
 * Licensed to JumpMind Inc under one or more contributor
 * license agreements.  See the NOTICE file distributed
 * with this work for additional information regarding
 * copyright ownership.  JumpMind Inc licenses this file
 * to you under the GNU General Public License, version 3.0 (GPLv3)
 * (the "License"); you may not use this file except in compliance
 * with the License.
 *
 * You should have received a copy of the GNU General Public License,
 * version 3.0 (GPLv3) along with this library; if not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.jumpmind.metl.core.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.jumpmind.exception.IoException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

abstract public class AbstractXMLFactory {
    
    protected final Logger log = LoggerFactory.getLogger(getClass());
    
    public AbstractXMLFactory() {
        refresh();
    }
    
    synchronized public void refresh() {
        reset();
        loadComponentsForClassloader(getClass().getClassLoader());
        // TODO in the future load from other resources
    }
    
    abstract protected void reset();

    abstract protected void loadComponentsForClassloader(ClassLoader classLoader);
    
    protected List<InputStream> loadResources(final String name, final ClassLoader classLoader) {
        try {
            Set<URL> urls = new HashSet<>();
            final List<InputStream> list = new ArrayList<InputStream>();
            final Enumeration<URL> systemResources = (classLoader == null ? ClassLoader.getSystemClassLoader() : classLoader)
                    .getResources(name);
            while (systemResources.hasMoreElements()) {
                URL url  = systemResources.nextElement();
                if (!urls.contains(url)) {
                    list.add(url.openStream());
                    urls.add(url);
                }
            }
            return list;
        } catch (IOException e) {
            throw new IoException(e);
        }
    }

}
