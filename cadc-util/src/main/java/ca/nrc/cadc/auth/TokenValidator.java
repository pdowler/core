/*
 ************************************************************************
 *******************  CANADIAN ASTRONOMY DATA CENTRE  *******************
 **************  CENTRE CANADIEN DE DONNÉES ASTRONOMIQUES  **************
 *
 *  (c) 2021.                            (c) 2021.
 *  Government of Canada                 Gouvernement du Canada
 *  National Research Council            Conseil national de recherches
 *  Ottawa, Canada, K1A 0R6              Ottawa, Canada, K1A 0R6
 *  All rights reserved                  Tous droits réservés
 *
 *  NRC disclaims any warranties,        Le CNRC dénie toute garantie
 *  expressed, implied, or               énoncée, implicite ou légale,
 *  statutory, of any kind with          de quelque nature que ce
 *  respect to the software,             soit, concernant le logiciel,
 *  including without limitation         y compris sans restriction
 *  any warranty of merchantability      toute garantie de valeur
 *  or fitness for a particular          marchande ou de pertinence
 *  purpose. NRC shall not be            pour un usage particulier.
 *  liable in any event for any          Le CNRC ne pourra en aucun cas
 *  damages, whether direct or           être tenu responsable de tout
 *  indirect, special or general,        dommage, direct ou indirect,
 *  consequential or incidental,         particulier ou général,
 *  arising from the use of the          accessoire ou fortuit, résultant
 *  software.  Neither the name          de l'utilisation du logiciel. Ni
 *  of the National Research             le nom du Conseil National de
 *  Council of Canada nor the            Recherches du Canada ni les noms
 *  names of its contributors may        de ses  participants ne peuvent
 *  be used to endorse or promote        être utilisés pour approuver ou
 *  products derived from this           promouvoir les produits dérivés
 *  software without specific prior      de ce logiciel sans autorisation
 *  written permission.                  préalable et particulière
 *                                       par écrit.
 *
 *  This file is part of the             Ce fichier fait partie du projet
 *  OpenCADC project.                    OpenCADC.
 *
 *  OpenCADC is free software:           OpenCADC est un logiciel libre ;
 *  you can redistribute it and/or       vous pouvez le redistribuer ou le
 *  modify it under the terms of         modifier suivant les termes de
 *  the GNU Affero General Public        la “GNU Affero General Public
 *  License as published by the          License” telle que publiée
 *  Free Software Foundation,            par la Free Software Foundation
 *  either version 3 of the              : soit la version 3 de cette
 *  License, or (at your option)         licence, soit (à votre gré)
 *  any later version.                   toute version ultérieure.
 *
 *  OpenCADC is distributed in the       OpenCADC est distribué
 *  hope that it will be useful,         dans l’espoir qu’il vous
 *  but WITHOUT ANY WARRANTY;            sera utile, mais SANS AUCUNE
 *  without even the implied             GARANTIE : sans même la garantie
 *  warranty of MERCHANTABILITY          implicite de COMMERCIALISABILITÉ
 *  or FITNESS FOR A PARTICULAR          ni d’ADÉQUATION À UN OBJECTIF
 *  PURPOSE.  See the GNU Affero         PARTICULIER. Consultez la Licence
 *  General Public License for           Générale Publique GNU Affero
 *  more details.                        pour plus de détails.
 *
 *  You should have received             Vous devriez avoir reçu une
 *  a copy of the GNU Affero             copie de la Licence Générale
 *  General Public License along         Publique GNU Affero avec
 *  with OpenCADC.  If not, see          OpenCADC ; si ce n’est
 *  <http://www.gnu.org/licenses/>.      pas le cas, consultez :
 *                                       <http://www.gnu.org/licenses/>.
 *
 ************************************************************************
 */

package ca.nrc.cadc.auth;

import ca.nrc.cadc.auth.NotAuthenticatedException.OAuth2Error;

import java.security.AccessControlException;
import java.util.List;
import java.util.Set;

import javax.security.auth.Subject;

import org.apache.log4j.Logger;

/**
 * Utility class to validate CADC tokens.
 * 
 * @author majorb
 *
 */
public class TokenValidator {
    
    private static Logger log = Logger.getLogger(TokenValidator.class);
    
    /**
     * Method to validate CADC-style cookie and token principals, used by the
     * CADC AuthenticatorImpl(s).
     * 
     * @param subject The subject containing extracted principals
     * @return The same subject but with associated public credentials
     * @throws AccessControlException 
     */
    public static Subject validateTokens(Subject subject) throws NotAuthenticatedException {
        
        // cookies
        Set<CookiePrincipal> cookiePrincipals = subject.getPrincipals(CookiePrincipal.class);
        log.debug("validateTokens: found " + cookiePrincipals.size() + " cookie principals");
        if (!cookiePrincipals.isEmpty()) {
            SSOCookieManager ssoCookieManager = new SSOCookieManager();
            for (CookiePrincipal p : cookiePrincipals) {
                try {
                    DelegationToken cookieToken = ssoCookieManager.parse(p.getValue());
                    subject.getPrincipals().addAll(cookieToken.getIdentityPrincipals());
                    List<SSOCookieCredential> cookieCredentialList =
                        ssoCookieManager.getSSOCookieCredentials(p.getValue());
                    log.debug("Adding " + cookieCredentialList.size() + " cookie credentials to subject");
                    subject.getPublicCredentials().addAll(cookieCredentialList);
                } catch (InvalidDelegationTokenException ex) {
                    throw new NotAuthenticatedException("invalid cookie: " + ex.getMessage(), ex);
                }
            }
        }
        
        // tokens
        Set<AuthorizationTokenPrincipal> tokenPrincipals = subject.getPrincipals(AuthorizationTokenPrincipal.class);
        log.debug("validateTokens: found " + tokenPrincipals.size() + " token principals");
        for (AuthorizationTokenPrincipal p : tokenPrincipals) {
            String token = p.getName();
            // parse the token into challenge type and credentials.  If only credentials available (no type), assume
            // the deprecated delegation token
            String challengeType = null;
            String credentials = null;
            int spaceIndex = token.indexOf(" ");
            if (spaceIndex == -1) {
                challengeType = AuthenticationUtil.TOKEN_TYPE_CADC;
                credentials = token;
            } else {
                challengeType = token.substring(0, spaceIndex).trim();
                credentials = token.substring(spaceIndex + 1).trim();
                if (!AuthenticationUtil.CHALLENGE_TYPE_BEARER.equals(challengeType) &&
                    !AuthenticationUtil.CHALLENGE_TYPE_IVOA.equals(challengeType)) {
                    throw new NotAuthenticatedException(challengeType, OAuth2Error.INVALID_REQUEST,
                        "unsupported challenge type: " + challengeType);
                }
            }
            
            try {
                DelegationToken validatedToken = DelegationToken.parse(credentials);
                subject.getPrincipals().add(validatedToken.getUser());
                // When scope is introduced, add the scope from the delegation token to
                // the authorization token.
                AuthorizationToken authToken = new AuthorizationToken(challengeType, credentials);
                log.debug("Adding token credential to subject");
                subject.getPublicCredentials().add(authToken);
            } catch (InvalidDelegationTokenException ex) {
                throw new NotAuthenticatedException(challengeType, OAuth2Error.INVALID_TOKEN, ex.getMessage(), ex);
            }
        }
        
        return subject;
    }

}
