// The local gateway routes both FD and report APIs; an override can target another base.
const apiBaseOverride = new URLSearchParams(window.location.search).get('apiBase');
const API_BASE_URL = (apiBaseOverride || 'http://localhost:8080/fd/v1').replace(/\/$/, '');

// Keep an explicitly chosen backend when moving between screens.
if (apiBaseOverride) {
  document.querySelectorAll('.topnav a').forEach(link => {
    const url = new URL(link.href);
    url.searchParams.set('apiBase', apiBaseOverride);
    link.href = url.href;
  });
}

/**
 * Calls the backend and resolves with the parsed JSON body on 2xx.
 * Rejects with an Error whose `.body` is the parsed ErrorResponse ({code, message})
 * and `.status` the HTTP status when the backend responds non-2xx, or a plain Error
 * on network failure.
 */
async function apiRequest(method, path, payload) {
  const options = { method, headers: {} };
  if (payload !== undefined) {
    options.headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(payload);
  }

  let response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, options);
  } catch (networkError) {
    throw new Error(
      `Could not reach the backend at ${API_BASE_URL}. Is fd-service running? (${networkError.message})`
    );
  }

  const responseBody = await response.json().catch(() => null);

  if (!response.ok) {
    const error = new Error(
      responseBody && responseBody.code
        ? `${responseBody.code}: ${responseBody.message}`
        : `Request failed with status ${response.status}`
    );
    error.body = responseBody;
    error.status = response.status;
    throw error;
  }

  return responseBody;
}

/**
 * POSTs a CreateFdAccountRequest-shaped payload to the backend.
 * Resolves with the parsed FdAccount response.
 */
async function createFdAccount(payload) {
  return apiRequest('POST', '/fd-accounts', payload);
}
