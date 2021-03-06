/*
 * Copyright (C) 2008 The Android Open Source Project
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

package nerd.tuxmobil.fahrplan.congress.net;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import javax.security.auth.x500.X500Principal;

import nerd.tuxmobil.fahrplan.congress.MyApp;

/**
 * Implements basic domain-name validation as specified by RFC2818.
 */
public class DomainNameChecker {

    private static Pattern QUICK_IP_PATTERN;

    static {
        try {
            QUICK_IP_PATTERN = Pattern.compile("^[a-f0-9\\.:]+$");
        } catch (PatternSyntaxException e) {
        }
    }

    private static final int ALT_DNS_NAME = 2;

    private static final int ALT_IPA_NAME = 7;

    private static final String LOG_TAG = "DomainNameChecker";

    /**
     * Checks the site certificate against the domain name of the site being
     * visited
     *
     * @param certificate The certificate to check
     * @param thisDomain  The domain name of the site being visited
     * @return True iff if there is a domain match as specified by RFC2818
     */
    public static boolean match(X509Certificate certificate, String thisDomain) {
        if ((certificate == null) || (thisDomain == null)
                || (thisDomain.length() == 0)) {
            MyApp.LogDebug(LOG_TAG, "no certificate/domain");
            return false;
        }

        thisDomain = thisDomain.toLowerCase();
        if (!isIpAddress(thisDomain)) {
            return matchDns(certificate, thisDomain);
        } else {
            return matchIpAddress(certificate, thisDomain);
        }
    }

    /**
     * @return True iff the domain name is specified as an IP address
     */
    private static boolean isIpAddress(String domain) {
        if ((domain == null) || (domain.length() == 0)) {
            return false;
        }

        boolean rval;
        try {
            // do a quick-dirty IP match first to avoid DNS lookup
            rval = QUICK_IP_PATTERN.matcher(domain).matches();
            if (rval) {
                rval = domain.equals(InetAddress.getByName(domain)
                        .getHostAddress());
            }
        } catch (UnknownHostException e) {
            String errorMessage = e.getMessage();
            if (errorMessage == null) {
                errorMessage = "unknown host exception";
            }

            MyApp.LogDebug(LOG_TAG, "DomainNameChecker.isIpAddress(): "
                    + errorMessage);

            rval = false;
        }

        return rval;
    }

    /**
     * Checks the site certificate against the IP domain name of the site being
     * visited
     *
     * @param certificate The certificate to check
     * @param thisDomain  The DNS domain name of the site being visited
     * @return True iff if there is a domain match as specified by RFC2818
     */
    private static boolean matchIpAddress(X509Certificate certificate, String thisDomain) {
        MyApp.LogDebug(LOG_TAG, "DomainNameChecker.matchIpAddress(): this domain: " + thisDomain);

        InetAddress[] ipAddr;
        try {
            ipAddr = InetAddress.getAllByName(thisDomain);
        } catch (UnknownHostException e1) {
            // TODO Auto-generated catch block
            e1.printStackTrace();
            return false;
        }

        String reverseDNS = ipAddr[0].getHostName();
        MyApp.LogDebug(LOG_TAG,
                "DomainNameChecker.matchIpAddress(): reverse address: " + reverseDNS);

        /* IP Adresse in Zertifikat suchen */
        try {
            Collection<?> subjectAltNames = certificate.getSubjectAlternativeNames();
            if (subjectAltNames != null) {
                for (Object subjectAltName : subjectAltNames) {
                    List<?> altNameEntry = (List<?>) (subjectAltName);
                    if ((altNameEntry != null) && (2 <= altNameEntry.size())) {
                        Integer altNameType = (Integer) (altNameEntry.get(0));
                        if (altNameType != null) {
                            if (altNameType == ALT_IPA_NAME) {
                                String altName = (String) (altNameEntry.get(1));
                                if (altName != null) {
                                    if (MyApp.DEBUG) {
                                        MyApp.LogDebug(LOG_TAG, "alternative IP: " + altName);
                                    }
                                    if (thisDomain.equalsIgnoreCase(altName)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (CertificateParsingException e) {
        }

        if (!reverseDNS.equals(thisDomain)) {
            // reverse lookup erfolgreich
            return match(certificate, reverseDNS);
        }

        return false;
    }

    /**
     * Checks the site certificate against the DNS domain name of the site being
     * visited
     *
     * @param certificate The certificate to check
     * @param thisDomain  The DNS domain name of the site being visited
     * @return True iff if there is a domain match as specified by RFC2818
     */
    private static boolean matchDns(X509Certificate certificate, String thisDomain) {
        MyApp.LogDebug(LOG_TAG, "matchDns cert vs " + thisDomain);
        boolean hasDns = false;
        try {
            Collection<?> subjectAltNames = certificate.getSubjectAlternativeNames();
            if (subjectAltNames != null) {
                for (Object subjectAltName : subjectAltNames) {
                    List<?> altNameEntry = (List<?>) (subjectAltName);
                    if ((altNameEntry != null) && (2 <= altNameEntry.size())) {
                        Integer altNameType = (Integer) (altNameEntry.get(0));
                        if (altNameType != null) {
                            if (altNameType == ALT_DNS_NAME) {
                                hasDns = true;
                                String altName = (String) (altNameEntry.get(1));
                                if (altName != null) {
                                    if (matchDns(thisDomain, altName)) {
                                        return true;
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                MyApp.LogDebug(LOG_TAG, "no SubjectAltNames, looking for SubjectDN");
                X500Principal dn = certificate.getSubjectX500Principal();
                String name = dn.getName(X500Principal.CANONICAL);
                String[] splitNames = name.split(",");
                for (String splitName : splitNames) {
                    MyApp.LogDebug(LOG_TAG, splitName);
                    if (splitName.length() > 3 && splitName.startsWith("cn=")) {
                        if (matchDns(thisDomain, splitName.substring(3))) {
                            return true;
                        }
                    }
                }
            }
        } catch (CertificateParsingException e) {
            // one way we can get here is if an alternative name starts with
            // '*' character, which is contrary to one interpretation of the
            // spec (a valid DNS name must start with a letter); there is no
            // good way around this, -> be strict and return false
            if (MyApp.DEBUG) {
                String errorMessage = e.getMessage();
                if (errorMessage == null) {
                    errorMessage = "failed to parse certificate";
                }

                MyApp.LogDebug(LOG_TAG, "DomainNameChecker.matchDns(): "
                        + errorMessage);
            }
        }

        return false;
    }

    /**
     * @param thisDomain The domain name of the site being visited
     * @param thatDomain The domain name from the certificate
     * @return True iff thisDomain matches thatDomain as specified by RFC2818
     */
    private static boolean matchDns(String thisDomain, String thatDomain) {
        MyApp.LogDebug(LOG_TAG, "DomainNameChecker.matchDns():"
                + " this domain: " + thisDomain + " that domain: "
                + thatDomain);

        if ((thisDomain == null) || (thisDomain.length() == 0)
                || (thatDomain == null) || (thatDomain.length() == 0)) {
            return false;
        }

        thatDomain = thatDomain.toLowerCase();

        // (a) domain name strings are equal, ignoring case: X matches X
        boolean rval = thisDomain.equals(thatDomain);
        if (!rval) {
            String[] thisDomainTokens = thisDomain.split("\\.");
            String[] thatDomainTokens = thatDomain.split("\\.");

            int thisDomainTokensNum = thisDomainTokens.length;
            int thatDomainTokensNum = thatDomainTokens.length;

            // (b) OR thatHost is a '.'-suffix of thisHost: Z.Y.X matches X
            if (thisDomainTokensNum >= thatDomainTokensNum) {
                for (int i = thatDomainTokensNum - 1; i >= 0; --i) {
                    rval = thisDomainTokens[i].equals(thatDomainTokens[i]);
                    if (!rval) {
                        // (c) OR we have a special *-match:
                        // Z.Y.X matches *.Y.X but does not match *.X
                        rval = ((i == 0) && (thisDomainTokensNum == thatDomainTokensNum));
                        if (rval) {
                            rval = thatDomainTokens[0].equals("*");
                            if (!rval) {
                                // (d) OR we have a *-component match:
                                // f*.com matches foo.com but not bar.com
                                rval = domainTokenMatch(thisDomainTokens[0],
                                        thatDomainTokens[0]);
                            }
                        }

                        break;
                    }
                }
            }
        }

        return rval;
    }

    /**
     * @param thisDomainToken The domain token from the current domain name
     * @param thatDomainToken The domain token from the certificate
     * @return True iff thisDomainToken matches thatDomainToken, using the
     * wildcard match as specified by RFC2818-3.1. For example, f*.com
     * must match foo.com but not bar.com
     */
    private static boolean domainTokenMatch(String thisDomainToken, String thatDomainToken) {
        if ((thisDomainToken != null) && (thatDomainToken != null)) {
            int starIndex = thatDomainToken.indexOf('*');
            if (starIndex >= 0) {
                if (thatDomainToken.length() - 1 <= thisDomainToken.length()) {
                    String prefix = thatDomainToken.substring(0, starIndex);
                    String suffix = thatDomainToken.substring(starIndex + 1);

                    return thisDomainToken.startsWith(prefix)
                            && thisDomainToken.endsWith(suffix);
                }
            }
        }

        return false;
    }
}
