import {
  afterEach, describe, expect, it, vi,
} from 'vitest';
import { timeApi } from './api.js';

function answer(status, text) {
  vi.stubGlobal('fetch', vi.fn(async () => ({
    ok: status >= 200 && status < 300, status, statusText: '', text: async () => text,
  })));
}

afterEach(() => vi.unstubAllGlobals());

describe('API replies', () => {
  it('reads no content from GET /timer as no running timer', async () => {
    answer(204, '');
    expect(await timeApi.current()).toBeUndefined();
  });

  it('treats an HTML page in place of JSON as the 404 CloudFront turned it from', async () => {
    answer(200, '<!doctype html><html><body>app</body></html>');
    expect(await timeApi.current()).toBeUndefined();
    await expect(timeApi.stop()).rejects.toMatchObject({ status: 404 });
  });
});
