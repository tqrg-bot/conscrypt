/*
 * Copyright 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.conscrypt;

import static android.system.OsConstants.SOL_SOCKET;
import static android.system.OsConstants.SO_SNDTIMEO;

import android.system.ErrnoException;
import android.system.Os;
import android.system.StructTimeval;
import dalvik.system.BlockGuard;
import dalvik.system.CloseGuard;
import java.io.FileDescriptor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketImpl;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.AlgorithmParameterSpec;
import java.security.spec.ECParameterSpec;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import org.apache.harmony.security.utils.AlgNameMapper;
import org.apache.harmony.security.utils.AlgNameMapperSource;
import org.conscrypt.GCMParameters;

class Platform {
    private static class NoPreloadHolder {
        public static final Platform MAPPER = new Platform();
    }

    /**
     * Runs all the setup for the platform that only needs to run once.
     */
    public static void setup() {
        NoPreloadHolder.MAPPER.ping();
    }

    /**
     * Just a placeholder to make sure the class is initialized.
     */
    private void ping() {
    }

    private Platform() {
        AlgNameMapper.setSource(new OpenSSLMapper());
    }

    private static class OpenSSLMapper implements AlgNameMapperSource {
        @Override
        public String mapNameToOid(String algName) {
            return NativeCrypto.OBJ_txt2nid_oid(algName);
        }

        @Override
        public String mapOidToName(String oid) {
            return NativeCrypto.OBJ_txt2nid_longName(oid);
        }
    }

    public static FileDescriptor getFileDescriptor(Socket s) {
        FileDescriptor ret = s.getFileDescriptor$();
        if (ret == null) {
            return new FileDescriptor();
        }
        return ret;
    }

    public static FileDescriptor getFileDescriptorFromSSLSocket(OpenSSLSocketImpl openSSLSocketImpl) {
        try {
            Field f_impl = Socket.class.getDeclaredField("impl");
            f_impl.setAccessible(true);
            Object socketImpl = f_impl.get(openSSLSocketImpl);
            Field f_fd = SocketImpl.class.getDeclaredField("fd");
            f_fd.setAccessible(true);
            // OpenJdk sockets start their life with a null FD.
            FileDescriptor ret = (FileDescriptor) f_fd.get(socketImpl);
            if (ret == null) {
                ret = new FileDescriptor();
            }
            return ret;
        } catch (Exception e) {
            throw new RuntimeException("Can't get FileDescriptor from socket", e);
        }
    }

    public static String getCurveName(ECParameterSpec spec) {
        return spec.getCurveName();
    }

    public static void setCurveName(ECParameterSpec spec, String curveName) {
        spec.setCurveName(curveName);
    }

    public static void setSocketWriteTimeout(Socket s, long timeoutMillis) throws SocketException {
        StructTimeval tv = StructTimeval.fromMillis(timeoutMillis);
        try {
            Os.setsockoptTimeval(s.getFileDescriptor$(), SOL_SOCKET, SO_SNDTIMEO, tv);
        } catch (ErrnoException errnoException) {
            throw errnoException.rethrowAsSocketException();
        }
    }

    public static void checkServerTrusted(X509TrustManager x509tm, X509Certificate[] chain,
            String authType, String host) throws CertificateException {
        if (x509tm instanceof TrustManagerImpl) {
            TrustManagerImpl tm = (TrustManagerImpl) x509tm;
            tm.checkServerTrusted(chain, authType, host);
        } else {
            // Use duck-typing to try and call the hostname aware checkServerTrusted if available.
            try {
                Method method = x509tm.getClass().getMethod("checkServerTrusted",
                        X509Certificate[].class,
                        String.class,
                        String.class);
                method.invoke(x509tm, chain, authType, host);
            } catch (NoSuchMethodException | IllegalAccessException e) {
                x509tm.checkServerTrusted(chain, authType);
            } catch (InvocationTargetException e) {
                if (e.getCause() instanceof CertificateException) {
                    throw (CertificateException) e.getCause();
                }
                throw new RuntimeException(e.getCause());
            }
        }
    }

    /**
     * Wraps an old AndroidOpenSSL key instance. This is not needed on platform
     * builds since we didn't backport, so return null.
     */
    public static OpenSSLKey wrapRsaKey(PrivateKey key) {
        return null;
    }

    /**
     * Logs to the system EventLog system.
     */
    public static void logEvent(String message) {
        try {
            Class processClass = Class.forName("android.os.Process");
            Object processInstance = processClass.newInstance();
            Method myUidMethod = processClass.getMethod("myUid", (Class[]) null);
            int uid = (Integer) myUidMethod.invoke(processInstance);

            Class eventLogClass = Class.forName("android.util.EventLog");
            Object eventLogInstance = eventLogClass.newInstance();
            Method writeEventMethod = eventLogClass.getMethod("writeEvent",
                    new Class[] { Integer.TYPE, Object[].class });
            writeEventMethod.invoke(eventLogInstance, 0x534e4554 /* SNET */,
                    new Object[] { "conscrypt", uid, message });
        } catch (Exception e) {
            // Do not log and fail silently
        }
    }

    /**
     * Returns true if the supplied hostname is an literal IP address.
     */
    public static boolean isLiteralIpAddress(String hostname) {
        return InetAddress.isNumeric(hostname);
    }

    /**
     * Wrap the SocketFactory with the platform wrapper if needed for compatability.
     * For the platform-bundled library we never need to wrap.
     */
    public static SSLSocketFactory wrapSocketFactoryIfNeeded(OpenSSLSocketFactoryImpl factory) {
        return factory;
    }

    /**
     * Convert from platform's GCMParameterSpec to our internal version.
     */
    public static GCMParameters fromGCMParameterSpec(AlgorithmParameterSpec params) {
        if (params instanceof GCMParameterSpec) {
            GCMParameterSpec gcmParams = (GCMParameterSpec) params;
            return new GCMParameters(gcmParams.getTLen(), gcmParams.getIV());
        }
        return null;
    }

    /**
     * Creates a platform version of {@code GCMParameterSpec}.
     */
    public static AlgorithmParameterSpec toGCMParameterSpec(int tagLenInBits, byte[] iv) {
        return new GCMParameterSpec(tagLenInBits, iv);
    }

    /*
     * CloseGuard functions.
     */

    public static CloseGuard closeGuardGet() {
        return CloseGuard.get();
    }

    public static void closeGuardOpen(Object guardObj, String message) {
        CloseGuard guard = (CloseGuard) guardObj;
        guard.open(message);
    }

    public static void closeGuardClose(Object guardObj) {
        CloseGuard guard = (CloseGuard) guardObj;
        guard.close();
    }

    public static void closeGuardWarnIfOpen(Object guardObj) {
        CloseGuard guard = (CloseGuard) guardObj;
        guard.warnIfOpen();
    }

    /*
     * BlockGuard functions.
     */

    public static void blockGuardOnNetwork() {
        BlockGuard.getThreadPolicy().onNetwork();
    }
}
