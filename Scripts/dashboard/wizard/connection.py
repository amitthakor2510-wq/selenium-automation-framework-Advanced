"""
"Can the test machine reach this address?" — the Check connection button next to every URL field.

One request, no redirects followed, nothing of the response body returned: only the status code, the
time taken, and (for a redirect) where it points. It exists to catch a typo or a stopped application
BEFORE a ten-minute run fails on it.

Safety: the address goes through the same validator as every test target (http/https only, no
credentials in the URL, no link-local / cloud-metadata / multicast / unspecified IP), and a host NAME
is resolved first and refused if ANY of its addresses falls in those ranges. Loopback and private
addresses are allowed on purpose — testing an app on localhost or the LAN is the normal case, and the
runner can already reach them. Requires the run.start permission (checked by the caller).
"""
import http.client
import ipaddress
import socket
import ssl
import time
import urllib.error
import urllib.parse
import urllib.request

from wizard import validators

TIMEOUT_SECONDS = 6.0


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):  # noqa: D401 — stdlib hook
        return None


def _blocked_address(ip_text):
    try:
        ip = ipaddress.ip_address(ip_text.split("%")[0])
    except ValueError:
        return True
    return ip.is_link_local or ip.is_multicast or ip.is_unspecified


def _resolve_check(host, port, resolver):
    try:
        infos = resolver(host, port, type=socket.SOCK_STREAM)
    except socket.gaierror:
        return "The address could not be found. Check it for typos."
    except OSError as e:
        return f"The address could not be looked up ({e.__class__.__name__})."
    if not infos:
        return "The address could not be found. Check it for typos."
    if any(_blocked_address(i[4][0]) for i in infos):
        return "That address points at a link-local / metadata / multicast range, which is not allowed as a test target."
    return None


def _result(ok, level, message, **extra):
    out = {"ok": ok, "level": level, "message": message}
    out.update(extra)
    return out


def _describe(status, ms, redirect):
    if 200 <= status < 300:
        return _result(True, "good", f"Reachable — answered {status} in {ms} ms.", status=status, ms=ms)
    if 300 <= status < 400:
        where = f" to {redirect}" if redirect else ""
        return _result(True, "good", f"Reachable — it redirects{where} ({status}). Tests will follow it.", status=status, ms=ms,
                       redirect=redirect)
    if status in (401, 403):
        return _result(True, "warn", f"Reachable, but it asks for a login ({status}). Add credentials in the optional "
                                     "section if the test needs to get past it.", status=status, ms=ms)
    if status == 404:
        return _result(True, "warn", "The server answered, but this exact address returned 'Not found' (404). "
                                     "Check the path.", status=status, ms=ms)
    if status >= 500:
        return _result(True, "warn", f"The server answered with an error ({status}). It is reachable but unhealthy.",
                       status=status, ms=ms)
    return _result(True, "good", f"Reachable — answered {status} in {ms} ms.", status=status, ms=ms)


def check(url, *, timeout=TIMEOUT_SECONDS, resolver=socket.getaddrinfo, opener=None, clock=time.monotonic):
    """-> {"ok", "level": good|warn|bad, "message", "status"?, "ms"?, "redirect"?}. Never raises."""
    problems = validators.validate_url(url, required=True)
    if problems:
        return _result(False, "bad", "Not a usable address: " + problems[0] + ".")
    u = urllib.parse.urlparse(url)
    port = u.port or (443 if u.scheme == "https" else 80)
    refusal = _resolve_check(u.hostname, port, resolver)
    if refusal:
        return _result(False, "bad", refusal)
    opener = opener or urllib.request.build_opener(_NoRedirect)
    headers = {"User-Agent": "automation-dashboard-connection-check", "Accept": "*/*"}
    last_error = None
    for method in ("HEAD", "GET"):
        req = urllib.request.Request(url, method=method, headers=headers)
        started = clock()
        try:
            with opener.open(req, timeout=timeout) as r:
                return _describe(r.status, int((clock() - started) * 1000), None)
        except urllib.error.HTTPError as e:
            ms = int((clock() - started) * 1000)
            redirect = e.headers.get("Location") if 300 <= e.code < 400 and e.headers else None
            e.close()
            if method == "HEAD" and e.code in (405, 501):
                continue  # server doesn't do HEAD: retry as GET
            if redirect and not redirect.lower().startswith(("http://", "https://")):
                redirect = urllib.parse.urljoin(url, redirect)
            return _describe(e.code, ms, redirect if redirect and redirect.lower().startswith(("http://", "https://")) else None)
        except urllib.error.URLError as e:
            last_error = e.reason
            break
        except (socket.timeout, TimeoutError):
            return _result(False, "bad", f"No answer within {int(timeout)} seconds. Is it running, and is the port right?")
        except (http.client.HTTPException, OSError) as e:
            last_error = e
            break
    return _result(False, "bad", _explain(last_error, timeout))


def _explain(err, timeout):
    if isinstance(err, (socket.timeout, TimeoutError)):
        return f"No answer within {int(timeout)} seconds. Is it running, and is the port right?"
    if isinstance(err, ssl.SSLCertVerificationError) or "CERTIFICATE_VERIFY_FAILED" in str(err):
        return "The site's security certificate was rejected (expired, self-signed or wrong name)."
    if isinstance(err, ssl.SSLError):
        return "A secure (https) connection could not be set up. Try http:// if the site is not https."
    if isinstance(err, ConnectionRefusedError):
        return "Nothing is listening at that address and port. Start the application, or check the port."
    if isinstance(err, socket.gaierror):
        return "The address could not be found. Check it for typos."
    return "Could not connect (" + (err.__class__.__name__ if err is not None else "unknown error") + ")."
