// Runs on the dedicated realtime audio thread, not the main thread - unlike
// the ScriptProcessorNode it replaces, main-thread jank (UI updates, GC,
// other WebRTC work) can't introduce irregular timing here. That irregular
// timing was feeding WebRTC's jitter buffer bursty packets, which made it
// grow the playout delay defensively - the likely source of the perceived lag.
class WasapiAudioProcessor extends AudioWorkletProcessor {
  constructor(options) {
    super();
    const { channels, sampleRate } = options.processorOptions;
    this.channels = channels;
    this.capacity = Math.ceil(sampleRate * 0.12); // 120ms hard ceiling per channel
    this.maxFill = Math.floor(sampleRate * 0.04); // trim target if network gets ahead: ~40ms
    this.buffers = Array.from({ length: channels }, () => new Float32Array(this.capacity));
    this.writeIdx = new Array(channels).fill(0);
    this.readIdx = new Array(channels).fill(0);
    this.fill = new Array(channels).fill(0);

    this.port.onmessage = (e) => this._enqueue(e.data);
  }

  _enqueue(perChannel) {
    for (let ch = 0; ch < this.channels; ch++) {
      const src = perChannel[ch];
      if (!src) continue;
      const buf = this.buffers[ch];
      for (let i = 0; i < src.length; i++) {
        buf[this.writeIdx[ch]] = src[i];
        this.writeIdx[ch] = (this.writeIdx[ch] + 1) % this.capacity;
        if (this.fill[ch] < this.capacity) this.fill[ch]++;
        else this.readIdx[ch] = (this.readIdx[ch] + 1) % this.capacity;
      }
      while (this.fill[ch] > this.maxFill) {
        this.readIdx[ch] = (this.readIdx[ch] + 1) % this.capacity;
        this.fill[ch]--;
      }
    }
  }

  process(_inputs, outputs) {
    const output = outputs[0];
    for (let ch = 0; ch < output.length; ch++) {
      const out = output[ch];
      const buf = this.buffers[ch] || this.buffers[0];
      const idxCh = this.buffers[ch] ? ch : 0;
      for (let i = 0; i < out.length; i++) {
        if (this.fill[idxCh] > 0) {
          out[i] = buf[this.readIdx[idxCh]];
          this.readIdx[idxCh] = (this.readIdx[idxCh] + 1) % this.capacity;
          this.fill[idxCh]--;
        } else {
          out[i] = 0;
        }
      }
    }
    return true;
  }
}

registerProcessor('wasapi-audio-processor', WasapiAudioProcessor);
