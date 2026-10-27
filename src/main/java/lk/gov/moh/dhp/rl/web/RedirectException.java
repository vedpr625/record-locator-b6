package lk.gov.moh.dhp.rl.web;

import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;

/**
 * Makes HAPI answer a request with a 307 Temporary Redirect.
 *
 * <p>A HAPI {@code @Read} method cannot issue a redirect by writing to the servlet
 * response: HAPI overwrites the status after the method returns (the client then sees
 * 404 even though a Location header is present). HAPI does, however, honour the status
 * code and response headers carried by a {@link BaseServerResponseException}, so the
 * redirect is expressed as one.
 */
public class RedirectException extends BaseServerResponseException {

    private static final long serialVersionUID = 1L;
    public static final int STATUS_TEMPORARY_REDIRECT = 307;

    public RedirectException(String location) {
        super(STATUS_TEMPORARY_REDIRECT, "Redirecting to content location");
        addResponseHeader("Location", location);
    }

    public String location() {
        var values = getResponseHeaders().get("Location");
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}
