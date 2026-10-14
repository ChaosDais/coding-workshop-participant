import { useState } from 'react';
import PresetList from '../components/PresetList.jsx';
import RequestForm from '../components/RequestForm.jsx';
import ResponsePanel from '../components/ResponsePanel.jsx';
import {
  buildUrl, fillPlaceholders, getServiceNames, parseJson, sendRequest,
} from '../services/apiClient.js';
import { PRESETS, VALUE_FIELDS } from '../services/presets.js';

const DEFAULT_SERVICE = 'workshop-service';

const serviceNames = [...new Set([DEFAULT_SERVICE, ...getServiceNames()])];

/**
 * Page for sending test requests to the backend and inspecting the raw responses.
 * @returns {JSX.Element} The API tester page.
 */
function ApiTester() {
  const [request, setRequest] = useState({
    service: DEFAULT_SERVICE, method: PRESETS[0].method, path: PRESETS[0].path, body: '',
  });
  const [values, setValues] = useState(
    Object.fromEntries(VALUE_FIELDS.map((field) => [field.name, ''])),
  );
  const [activePreset, setActivePreset] = useState(PRESETS[0]);
  const [response, setResponse] = useState();
  const [sending, setSending] = useState(false);

  const resolvedPath = fillPlaceholders(request.path, values);

  const updateRequest = (field, value) => {
    setRequest((current) => ({ ...current, [field]: value }));
    if (field === 'method' || field === 'path') {
      setActivePreset(undefined);
    }
  };

  const selectPreset = (preset) => {
    setActivePreset(preset);
    setRequest((current) => ({
      ...current, method: preset.method, path: preset.path, body: preset.body ?? '',
    }));
  };

  const send = async () => {
    setSending(true);
    const result = await sendRequest({
      ...request,
      path: resolvedPath,
      body: fillPlaceholders(request.body, values),
      accessToken: values.accessToken,
    });
    setResponse(result);
    setSending(false);

    // Presets name the response fields to remember, e.g. the new project's id or the tokens from sign-in.
    const parsed = result.ok && activePreset?.capture ? parseJson(result.body) : undefined;
    if (parsed) {
      const captured = Object.entries(activePreset.capture)
        .filter(([, field]) => typeof parsed[field] === 'string')
        .map(([name, field]) => [name, parsed[field]]);
      setValues((current) => ({ ...current, ...Object.fromEntries(captured) }));
    }
  };

  return (
    <main className="api-tester">
      <h1>API tester</h1>
      <div className="layout">
        <PresetList presets={PRESETS} activeId={activePreset?.id} onSelect={selectPreset} />
        <div className="panels">
          <RequestForm
            request={request}
            fields={VALUE_FIELDS}
            values={values}
            serviceNames={serviceNames}
            url={buildUrl(request.service, resolvedPath)}
            sending={sending}
            onChange={updateRequest}
            onValueChange={(name, value) => setValues((current) => ({ ...current, [name]: value }))}
            onSend={send}
          />
          <ResponsePanel response={response} />
        </div>
      </div>
    </main>
  );
}

export default ApiTester;
