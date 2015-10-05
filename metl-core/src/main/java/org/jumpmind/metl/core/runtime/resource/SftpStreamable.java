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
package org.jumpmind.metl.core.runtime.resource;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.jumpmind.exception.IoException;
import org.jumpmind.metl.core.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;

public class SftpStreamable implements IStreamable {

    protected String server;
    protected Integer port;
    protected String user;
    protected String password;
    protected String basePath;
    protected Integer connectionTimeout;
    protected boolean mustExist;
    
    protected static final Logger log = LoggerFactory.getLogger(SftpStreamable.class);

    public SftpStreamable(Resource resource, 
            String server,
            Integer port,
            String user,
            String password,
            String basePath, 
            Integer connectionTimeout,
            boolean mustExist) {
        
        this.server = server;
        this.port = port;
        this.user = user;
        this.password = password;
        this.basePath = basePath;
        this.connectionTimeout = connectionTimeout;
        this.mustExist = mustExist;
    }

    private boolean fileExists(ChannelSftp sftp, String filePath) {
        try {
                SftpATTRS attributes = sftp.stat(filePath);
                if (attributes != null) {
                    return true;
                } else {
                    return false;
                }
        } catch (SftpException e) {
            log.error("Error determing whether a remote file exsits. Error %s", e.getMessage());
            return false;
        }
    }

    protected void close(Session session, ChannelSftp sftp) {
    	if (sftp != null) {
            sftp.disconnect();
            sftp = null;
        }
    	if (session != null) {
    		session.disconnect();
    		session = null;
    	}
    }
    
    @Override
    public boolean requiresContentLength() {
        return false;
    }

    @Override
    public void setContentLength(int length) {
    }

    @Override
    public boolean supportsInputStream() {
        return true;
    }
    
    protected Session connect() {
        JSch jsch=new JSch();
        Session session = null;
        try {
            session = jsch.getSession(user, server, port);
            session.setPassword(password.getBytes());
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect(connectionTimeout);
            return session;
        } catch (JSchException e) {
            throw new IoException(e);
		}   
    }    

    @Override
    public InputStream getInputStream(String relativePath, boolean mustExist) {
    	Session session = null;
    	ChannelSftp sftp = null;
        try {
        	session = connect();
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect();
            sftp.cd(basePath);
            if (mustExist && !fileExists(sftp, relativePath)) {
            	SftpStreamable.this.close(session, sftp);
                throw new IoException("Could not find endpoint %s that was configured as MUST EXIST",relativePath);
            }
            return new CloseableInputStreamStream(sftp.get(relativePath), session, sftp);
        } catch (Exception e) {
            throw new IoException("Error getting the input stream for ssh endpoint.  Error %s", e.getMessage());
        } 
    }

    @Override
    public boolean supportsOutputStream() {
        return true;
    }

    @Override
    public OutputStream getOutputStream(String relativePath, boolean mustExist) {
    	Session session = null;
    	ChannelSftp sftp = null;
        try {
        	session = connect();
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect();
            sftp.cd(basePath);
            return new CloseableOutputStream(sftp.put(relativePath, ChannelSftp.OVERWRITE), session, sftp);
        } catch (Exception e) {            
            throw new IoException(e);
        } 
    }

    @Override
    public void close() {
    }

    @Override
    public boolean delete(String relativePath) {
    	Session session = null;
    	ChannelSftp sftp = null;
        try {
            session = connect();
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect();
            sftp.cd(basePath);
            sftp.rm(relativePath);
            return true;
        } catch (Exception e) {
            return false;
        } finally {
        	SftpStreamable.this.close(session, sftp);
        }
    }
    
    @Override
    public boolean supportsDelete() {
        return true;
    }

    @Override
    public String toString() {
        return basePath;
    }

    class CloseableOutputStream extends BufferedOutputStream {
        Session session;
        ChannelSftp sftp;

        public CloseableOutputStream(OutputStream os, Session session, ChannelSftp sftp) {
            super(os);
            this.session = session;
            this.sftp = sftp;
        }

        @Override
        public void close() throws IOException {
            super.close();
            SftpStreamable.this.close(session, sftp);
        }
    }

    class CloseableInputStreamStream extends BufferedInputStream {
        Session session;
        ChannelSftp sftp;

        public CloseableInputStreamStream(InputStream is, Session session, ChannelSftp sftp) {
            super(is);
            this.session = session;
            this.sftp = sftp;
        }

        @Override
        public void close() throws IOException {
            super.close();
            SftpStreamable.this.close(session, sftp);
        }
    }    
}