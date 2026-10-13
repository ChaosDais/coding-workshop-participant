import { useState } from 'react';
import PresetList from '../components/PresetList.jsx';
import RequestForm from '../components/RequestForm.jsx';
import ResponsePanel from '../components/ResponsePanel.jsx';
import {
  buildUrl, fillPath, getServiceNames, parseJson, sendRequest,
} from '../services/apiClient.js';
import { PRESETS } from '../services/presets.js';

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
  const [ids, setIds] = useState({ projectId: '', milestoneId: '' });
  const [activePreset, setActivePreset] = useState(PRESETS[0]);
  const [response, setResponse] = useState();
  const [sending, setSending] = useState(false);

  const resolvedPath = fillPath(request.path, ids);

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
    const result = await sendRequest({ ...request, path: resolvedPath });
    setResponse(result);
    setSending(false);

    const createdId = result.ok && activePreset?.captures ? parseJson(result.body)?.id : undefined;
    if (createdId) {
      setIds((current) => ({ ...current, [activePreset.captures]: createdId }));
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
            ids={ids}
            serviceNames={serviceNames}
            url={buildUrl(request.service, resolvedPath)}
            sending={sending}
            onChange={updateRequest}
            onIdChange={(name, value) => setIds((current) => ({ ...current, [name]: value }))}
            onSend={send}
          />
          <ResponsePanel response={response} />
        </div>
      </div>
    </main>
  );
}

export default ApiTester;
