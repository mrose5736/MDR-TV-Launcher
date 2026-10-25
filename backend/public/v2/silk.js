// Animated silk background (WebGL2), ported from the React "Silk" component for this
// buildless page. TV-oriented: no pointer input, half-resolution canvas, frame-rate cap.
// start() returns { update(options), stop() }, or null when WebGL2 is unavailable.
(function () {
  const VERTEX = `#version 300 es
in vec2 aPosition;
void main() { gl_Position = vec4(aPosition, 0.0, 1.0); }`;

  const SILK = `
uniform vec2 uView;
uniform float uScale;
uniform float uRotation;
uniform float uTime;
uniform float uWave;
uniform float uRipple;
uniform float uFold;
uniform vec2 uShift;

const float e = 2.71828182845904523536;

float noise(vec2 texCoord) {
  float G = e;
  vec2 r = (G * sin(G * texCoord));
  return fract(r.x * r.y * (1.0 + texCoord.x));
}

vec2 rotateUvs(vec2 uv, float angle) {
  float c = cos(angle);
  float s = sin(angle);
  mat2 rot = mat2(c, -s, s, c);
  return rot * uv;
}

float silk(vec2 vUv) {
  vec2 uv = rotateUvs(vUv * uScale, uRotation);
  vec2 tex = uv * uScale + uShift;
  float tOffset = uTime;
  tex.y += 0.03 * uWave * sin(8.0 * tex.x - tOffset);
  return 0.6 + 0.4 * uFold * sin(5.0 * (tex.x + tex.y + cos(3.0 * tex.x + 5.0 * tex.y) + 0.02 * tOffset) + uRipple * sin(20.0 * (tex.x + tex.y - 0.1 * tOffset)));
}
`;

  const FIELD = `#version 300 es
precision highp float;
${SILK}
out vec4 outColor;
void main() {
  vec2 vUv = gl_FragCoord.xy / uView;
  float n = clamp((silk(vUv) - 0.2) / 0.8, 0.0, 1.0);
  outColor = vec4(pow(n, 6.0), 0.0, 0.0, 1.0);
}`;

  const COMPOSITE = `#version 300 es
precision highp float;
${SILK}
uniform sampler2D uBloom;
uniform vec3 uColor;
uniform vec3 uSheenColor;
uniform float uNoiseIntensity;
uniform float uDepth;
uniform float uSheen;
uniform float uGlow;
uniform vec2 uLight;
uniform vec2 uStep;
uniform float uPresence;
out vec4 outColor;

vec3 softClip(vec3 c) {
  vec3 over = max(c - 0.75, 0.0);
  return min(c, 0.75) + 0.25 * (1.0 - exp(-over * 4.0));
}

void main() {
  vec2 vUv = gl_FragCoord.xy / uView;
  float pattern = silk(vUv);
  float rnd = noise(gl_FragCoord.xy);
  float n = clamp((pattern - 0.2) / 0.8, 0.0, 1.0);

  float gx = silk(vUv + vec2(uStep.x, 0.0)) - pattern;
  float gy = silk(vUv + vec2(0.0, uStep.y)) - pattern;
  vec3 normal = normalize(vec3(-gx * 26.0, -gy * 26.0, 1.0));
  vec3 toLight = normalize(vec3(uLight, 0.85));
  float relief = 1.0 + (dot(normal, toLight) - toLight.z) * 1.8 * uDepth;
  vec3 halfway = normalize(toLight + vec3(0.0, 0.0, 1.0));
  float gloss = pow(max(dot(normal, halfway), 0.0), 48.0) * smoothstep(0.25, 0.85, n);
  float crest = pow(n, 5.0);
  float shine = (gloss * 0.85 + crest * 0.4) * uSheen;

  float bloom = 0.0;
  if (uGlow > 0.0) {
    bloom += textureLod(uBloom, vUv, 1.0).r * 0.35;
    bloom += textureLod(uBloom, vUv, 2.0).r * 0.3;
    bloom += textureLod(uBloom, vUv, 3.0).r * 0.2;
    bloom += textureLod(uBloom, vUv, 4.0).r * 0.15;
  }

  float grain = rnd / 15.0 * uNoiseIntensity;
  float floorValue = mix(0.2, 0.03, uDepth);
  float curve = uDepth > 0.0 ? pow(n, 1.0 + 1.4 * uDepth) : n;
  float shade = uDepth > 0.0 ? floorValue + (1.0 - floorValue) * curve : pattern;
  vec3 tone = mix(uColor, pow(uColor, vec3(1.45)), (1.0 - n) * uDepth);
  vec3 lit = tone * shade * relief - vec3(grain);
  vec3 tint = mix(uColor, uSheenColor, 0.8);
  vec3 extra = tint * (shine + bloom * uGlow * 0.55);
  float blend = clamp((uSheen + uGlow) * 4.0, 0.0, 1.0);
  vec3 result = mix(clamp(lit, 0.0, 1.0), clamp(softClip(lit + extra), 0.0, 1.0), blend);
  outColor = vec4(result * uPresence, 1.0);
}`;

  const INTRO_SECONDS = 2.2;
  const INTRO_RUSH = 1.6;
  const LIGHT = [-0.55, 0.7];
  const DEFAULTS = {
    speed: 2, scale: 1, color: '#4a5d85', sheenColor: '#f0c77e', noiseIntensity: 1.2, rotation: 0,
    sheen: 0.5, depth: 0.6, glow: 0.3, ripple: 1, wave: 1, resolution: 0.5, fps: 30
  };

  const ease = t => t * t * t * (t * (t * 6 - 15) + 10);
  const rgb = hex => {
    const n = parseInt(String(hex).replace('#', '').padEnd(6, '0').slice(0, 6), 16) || 0;
    return [(n >> 16 & 255) / 255, (n >> 8 & 255) / 255, (n & 255) / 255];
  };
  function compile(gl, type, source) {
    const shader = gl.createShader(type);
    gl.shaderSource(shader, source);
    gl.compileShader(shader);
    if (gl.getShaderParameter(shader, gl.COMPILE_STATUS)) return shader;
    gl.deleteShader(shader);
    return null;
  }
  function link(gl, fragmentSource) {
    const vertex = compile(gl, gl.VERTEX_SHADER, VERTEX);
    const fragment = compile(gl, gl.FRAGMENT_SHADER, fragmentSource);
    const program = gl.createProgram();
    if (!vertex || !fragment || !program) return null;
    gl.attachShader(program, vertex);
    gl.attachShader(program, fragment);
    gl.bindAttribLocation(program, 0, 'aPosition');
    gl.linkProgram(program);
    if (!gl.getProgramParameter(program, gl.LINK_STATUS)) return null;
    const uniforms = {};
    for (let i = 0, n = gl.getProgramParameter(program, gl.ACTIVE_UNIFORMS); i < n; i++) {
      const info = gl.getActiveUniform(program, i);
      uniforms[info.name] = gl.getUniformLocation(program, info.name);
    }
    return {program, uniforms};
  }

  function start(container, options) {
    const canvas = document.createElement('canvas');
    canvas.setAttribute('aria-hidden', 'true');
    canvas.style.cssText = 'display:block;width:100%;height:100%';
    const gl = canvas.getContext('webgl2', {alpha: false, antialias: false, depth: false, stencil: false});
    if (!gl) return null;
    const field = link(gl, FIELD), composite = link(gl, COMPOSITE);
    if (!field || !composite) return null;
    container.append(canvas);

    let o = {...DEFAULTS, ...options};
    const vao = gl.createVertexArray();
    gl.bindVertexArray(vao);
    gl.bindBuffer(gl.ARRAY_BUFFER, gl.createBuffer());
    gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 3, -1, -1, 3]), gl.STATIC_DRAW);
    gl.enableVertexAttribArray(0);
    gl.vertexAttribPointer(0, 2, gl.FLOAT, false, 0, 0);

    const texture = gl.createTexture(), framebuffer = gl.createFramebuffer(), target = {w: 0, h: 0};
    function sizeTarget(w, h) {
      if (target.w === w && target.h === h) return;
      Object.assign(target, {w, h});
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, w, h, 0, gl.RGBA, gl.UNSIGNED_BYTE, null);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR_MIPMAP_LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
      gl.framebufferTexture2D(gl.FRAMEBUFFER, gl.COLOR_ATTACHMENT0, gl.TEXTURE_2D, texture, 0);
    }

    let flow = 0, intro = 0, last = 0, raf = 0, css = {w: 1, h: 1};
    function shared(u, w, h) {
      const unfold = ease(intro);
      gl.uniform2f(u.uView, w, h);
      gl.uniform1f(u.uScale, o.scale);
      gl.uniform1f(u.uRotation, o.rotation);
      gl.uniform1f(u.uTime, flow - INTRO_RUSH * (1 - unfold));
      gl.uniform1f(u.uWave, o.wave);
      gl.uniform1f(u.uRipple, o.ripple);
      gl.uniform1f(u.uFold, 0.25 + 0.75 * unfold);
      gl.uniform2f(u.uShift, 0, 0);
    }
    function render() {
      gl.bindVertexArray(vao);
      if (o.glow > 0) {
        sizeTarget(Math.max(16, canvas.width >> 2), Math.max(16, canvas.height >> 2));
        gl.bindFramebuffer(gl.FRAMEBUFFER, framebuffer);
        gl.viewport(0, 0, target.w, target.h);
        gl.useProgram(field.program);
        shared(field.uniforms, target.w, target.h);
        gl.drawArrays(gl.TRIANGLES, 0, 3);
        gl.bindTexture(gl.TEXTURE_2D, texture);
        gl.generateMipmap(gl.TEXTURE_2D);
      }
      const u = composite.uniforms;
      gl.bindFramebuffer(gl.FRAMEBUFFER, null);
      gl.viewport(0, 0, canvas.width, canvas.height);
      gl.useProgram(composite.program);
      shared(u, canvas.width, canvas.height);
      gl.uniform3fv(u.uColor, rgb(o.color));
      gl.uniform3fv(u.uSheenColor, rgb(o.sheenColor));
      gl.uniform1f(u.uNoiseIntensity, o.noiseIntensity);
      gl.uniform1f(u.uDepth, Math.min(1, Math.max(0, o.depth)));
      gl.uniform1f(u.uSheen, o.sheen);
      gl.uniform1f(u.uGlow, o.glow);
      gl.uniform2f(u.uLight, LIGHT[0], LIGHT[1]);
      gl.uniform2f(u.uStep, 1 / css.w, 1 / css.h);
      gl.uniform1f(u.uPresence, 1 - Math.pow(1 - intro, 3));
      gl.activeTexture(gl.TEXTURE0);
      gl.bindTexture(gl.TEXTURE_2D, texture);
      gl.uniform1i(u.uBloom, 0);
      gl.drawArrays(gl.TRIANGLES, 0, 3);
    }
    function frame(now) {
      raf = requestAnimationFrame(frame);
      if (document.hidden || now - last < 1000 / o.fps - 1) return;
      const dt = last ? Math.min(0.1, (now - last) / 1000) : 1 / 30;
      last = now;
      flow += dt * 0.1 * o.speed;
      if (intro < 1) intro = Math.min(1, intro + dt / INTRO_SECONDS);
      render();
    }
    function resize() {
      css = {w: Math.max(1, container.clientWidth), h: Math.max(1, container.clientHeight)};
      canvas.width = Math.max(1, Math.round(css.w * o.resolution));
      canvas.height = Math.max(1, Math.round(css.h * o.resolution));
      render();
    }
    const observer = new ResizeObserver(resize);
    observer.observe(container);
    raf = requestAnimationFrame(frame);

    return {
      update(next) { o = {...DEFAULTS, ...next}; resize(); },
      stop() {
        cancelAnimationFrame(raf);
        observer.disconnect();
        canvas.remove();
        gl.getExtension('WEBGL_lose_context')?.loseContext();
      }
    };
  }

  window.TVSilk = {start};
})();
