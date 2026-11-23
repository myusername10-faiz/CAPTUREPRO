/* CapturePro — Phase 1 Interactive Prototype Logic */
(function () {
  'use strict';

  const RES_INFO = {
    '480p': { label: '480p SD', dims: '854 × 480', mbPerMin: 28 },
    '720p': { label: '720p HD', dims: '1280 × 720', mbPerMin: 52 },
    '1080p': { label: '1080p Full HD', dims: '1920 × 1080', mbPerMin: 95 },
    '1440p': { label: '1440p QHD', dims: '2560 × 1440', mbPerMin: 145 },
  };

  const ERROR_CATALOG = [
    {
      id: 'proj_denied',
      title: 'Screen Recording Permission Denied',
      desc: 'MediaProjection consent was canceled. Android requires explicit user approval for every capture session before screen pixels can be accessed.',
      actionLabel: 'Request Consent Again',
      action: () => openModal('modal-consent'),
    },
    {
      id: 'mic_denied',
      title: 'Microphone Permission Denied',
      desc: 'RECORD_AUDIO permission is required to capture microphone input. You can still record internal audio or muted video.',
      actionLabel: 'Open Audio Settings',
      action: () => navigateTo('screen-audio'),
    },
    {
      id: 'cam_denied',
      title: 'Camera Permission Denied',
      desc: 'CAMERA permission is needed for the Facecam overlay. Screen recording can continue without Facecam.',
      actionLabel: 'Open Facecam Settings',
      action: () => navigateTo('screen-facecam'),
    },
    {
      id: 'internal_unavail',
      title: 'Internal Audio Unavailable',
      desc: 'The active app (such as a voice call or DRM-protected player) restricts AudioPlaybackCapture. Switch to Microphone audio as an alternative.',
      actionLabel: 'Enable Microphone Fallback',
      action: () => {
        state.micAudio = true;
        state.internalAudio = false;
        syncAudioUI();
        showToast('Switched to Microphone-only mode');
      },
    },
    {
      id: 'unsupported_res',
      title: '1440p Resolution Unsupported',
      desc: 'This device display or hardware MediaCodec encoder does not support 2560 × 1440 output. CapturePro can fall back to 1080p Full HD.',
      actionLabel: 'Use 1080p Full HD',
      action: () => {
        state.resolution = '1080p';
        syncQualityUI();
        showToast('Resolution set to 1080p Full HD');
      },
    },
    {
      id: 'unsupported_fps',
      title: '60 FPS Frame Rate Unsupported',
      desc: 'Hardware encoder reported a maximum sustained frame rate of 30 FPS at the current resolution.',
      actionLabel: 'Switch to 30 FPS',
      action: () => {
        state.fps = 30;
        syncQualityUI();
        showToast('Frame rate set to 30 FPS');
      },
    },
    {
      id: 'low_storage',
      title: 'Insufficient Device Storage',
      desc: 'Available storage dropped below the safety threshold (2.0 GB). Free up space or delete older recordings before starting a long session.',
      actionLabel: 'Manage Storage',
      action: () => navigateTo('screen-storage'),
    },
    {
      id: 'encoder_fail',
      title: 'Encoder Initialization Failure',
      desc: 'MediaCodec hardware video encoder failed to allocate buffers (another app may hold codec resources). Try 1080p 30 FPS.',
      actionLabel: 'Reset to Safe Profile',
      action: () => {
        state.resolution = '1080p';
        state.fps = 30;
        syncQualityUI();
        showToast('Applied safe 1080p • 30 FPS profile');
      },
    },
    {
      id: 'rec_interrupted',
      title: 'Recording Interrupted',
      desc: 'An incoming phone call or system audio focus loss interrupted the capture session. Partial footage up to this point was safely muxed.',
      actionLabel: 'View Library',
      action: () => navigateTo('screen-library'),
    },
    {
      id: 'proj_stopped',
      title: 'MediaProjection Stopped by System',
      desc: 'Android revoked the active MediaProjection token (e.g., screen locked or cast started). CapturePro finalized and saved the recording.',
      actionLabel: 'Return to Dashboard',
      action: () => navigateTo('screen-dashboard'),
    },
    {
      id: 'save_fail',
      title: 'File Saving Failure',
      desc: 'Could not write output to the selected Storage Access Framework folder. Switch back to default MediaStore Movies/CapturePro.',
      actionLabel: 'Use MediaStore Default',
      action: () => {
        state.saveDest = 'mediastore';
        const sel = document.getElementById('saveDestSelect');
        if (sel) sel.value = 'mediastore';
        showToast('Save destination reset to MediaStore');
      },
    },
  ];

  const state = {
    theme: 'dark',
    recState: 'IDLE', // IDLE | PREPARING | RECORDING | PAUSED | STOPPING | SAVED
    elapsedSec: 0,
    timerInterval: null,
    resolution: '1440p',
    fps: 60,
    orientation: 'Portrait',
    internalAudio: true,
    micAudio: true,
    simInternalBlocked: false,
    simCapFallback: false,
    micSource: 'Default Mic',
    facecamEnabled: false,
    facecamShape: 'circle',
    facecamPos: 'bottom-right',
    facecamBorder: true,
    floatingControls: true,
    notifications: true,
    saveDest: 'mediastore',
    namingPattern: 'CapturePro_YYYYMMDD_HHMMSS',
    lowStorageWarn: true,
    deleteConfirm: true,
    consentScope: 'Entire Screen',
    libTab: 'all',
    libSearch: '',
    libSort: 'newest',
    activeItemId: null,
    pendingDeleteId: null,
    pendingRenameId: null,
    media: [],
  };

  // Generate crisp SVG/Canvas thumbnail data URLs
  function makeThumbnail(title, subtitle, accentColor, isShot) {
    const c = document.createElement('canvas');
    c.width = 320;
    c.height = 180;
    const ctx = c.getContext('2d');
    const grad = ctx.createLinearGradient(0, 0, 320, 180);
    grad.addColorStop(0, '#131826');
    grad.addColorStop(1, '#1e263b');
    ctx.fillStyle = grad;
    ctx.fillRect(0, 0, 320, 180);

    ctx.fillStyle = accentColor;
    ctx.globalAlpha = 0.22;
    ctx.beginPath();
    ctx.arc(265, 42, 64, 0, Math.PI * 2);
    ctx.fill();
    ctx.globalAlpha = 1;

    ctx.strokeStyle = 'rgba(255,255,255,0.12)';
    ctx.lineWidth = 2;
    ctx.strokeRect(18, 20, 284, 140);

    ctx.fillStyle = accentColor;
    ctx.fillRect(32, 36, isShot ? 68 : 54, 18);
    ctx.fillStyle = '#ffffff';
    ctx.font = 'bold 10px Inter, sans-serif';
    ctx.fillText(isShot ? 'SCREENSHOT' : 'VIDEO', 38, 49);

    ctx.fillStyle = '#f8fafc';
    ctx.font = 'bold 16px Inter, sans-serif';
    ctx.fillText(title.slice(0, 24), 32, 92);

    ctx.fillStyle = '#94a3b8';
    ctx.font = '12px Inter, sans-serif';
    ctx.fillText(subtitle, 32, 116);

    return c.toDataURL('image/png');
  }

  function seedSampleMedia() {
    const now = Date.now();
    state.media = [
      {
        id: 'm1',
        type: 'video',
        name: 'CapturePro_20261123_142010.mp4',
        durationSec: 185,
        sizeMB: 274.5,
        resolution: '1440p QHD',
        fps: 60,
        dateTs: now - 3600 * 1000 * 2,
        dateLabel: 'Today, 14:20',
        thumb: makeThumbnail('App Demo Walkthrough', '1440p QHD • 60 FPS • Internal + Mic', '#6366f1', false),
      },
      {
        id: 'm2',
        type: 'video',
        name: 'Gameplay_Session_1080p.mp4',
        durationSec: 412,
        sizeMB: 652.0,
        resolution: '1080p Full HD',
        fps: 60,
        dateTs: now - 3600 * 1000 * 26,
        dateLabel: 'Yesterday, 19:45',
        thumb: makeThumbnail('Mobile Gaming Capture', '1080p • 60 FPS • Internal Audio', '#3b82f6', false),
      },
      {
        id: 'm3',
        type: 'screenshot',
        name: 'Screenshot_20261122_1104.png',
        durationSec: 0,
        sizeMB: 3.4,
        resolution: '1440 × 3120',
        fps: 0,
        dateTs: now - 3600 * 1000 * 30,
        dateLabel: 'Yesterday, 11:04',
        thumb: makeThumbnail('UI Layout Snapshot', '1440 × 3120 • PNG', '#10b981', true),
      },
      {
        id: 'm4',
        type: 'video',
        name: 'Tutorial_Screen_Notes.mp4',
        durationSec: 94,
        sizeMB: 81.2,
        resolution: '720p HD',
        fps: 30,
        dateTs: now - 3600 * 1000 * 72,
        dateLabel: '3 days ago',
        thumb: makeThumbnail('Tutorial Recording', '720p HD • 30 FPS • Mic Only', '#8b5cf6', false),
      },
    ];
  }

  // Helpers
  function fmtDuration(sec) {
    const h = Math.floor(sec / 3600);
    const m = Math.floor((sec % 3600) / 60);
    const s = sec % 60;
    const pad = (n) => String(n).padStart(2, '0');
    return `${pad(h)}:${pad(m)}:${pad(s)}`;
  }

  function fmtShortDuration(sec) {
    const m = Math.floor(sec / 60);
    const s = sec % 60;
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
  }

  let toastTimer = null;
  function showToast(msg) {
    const el = document.getElementById('toast');
    if (!el) return;
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove('show'), 2600);
  }

  function openModal(id) {
    const el = document.getElementById(id);
    if (el) el.classList.add('open');
  }

  function closeModal(id) {
    const el = document.getElementById(id);
    if (el) el.classList.remove('open');
  }

  function navigateTo(screenId) {
    document.querySelectorAll('.screen').forEach((s) => s.classList.remove('active'));
    const target = document.getElementById(screenId);
    if (target) target.classList.add('active');

    const topMap = {
      'screen-dashboard': 'screen-dashboard',
      'screen-library': 'screen-library',
      'screen-storage': 'screen-storage',
      'screen-settings': 'screen-settings',
      'screen-audio': 'screen-settings',
      'screen-quality': 'screen-settings',
      'screen-facecam': 'screen-settings',
      'screen-privacy': 'screen-settings',
    };
    const activeTab = topMap[screenId] || 'screen-dashboard';
    document.querySelectorAll('.nav-item').forEach((btn) => {
      btn.classList.toggle('active', btn.dataset.nav === activeTab);
    });
    const main = document.getElementById('appMain');
    if (main) main.scrollTop = 0;
  }

  // Theme
  function setTheme(theme) {
    state.theme = theme;
    document.documentElement.setAttribute('data-theme', theme);
    const icon = document.getElementById('themeIcon');
    if (icon) {
      icon.setAttribute('data-lucide', theme === 'dark' ? 'sun' : 'moon');
      if (window.lucide) window.lucide.createIcons();
    }
    const settingsToggle = document.getElementById('settingsThemeToggle');
    if (settingsToggle) settingsToggle.checked = theme === 'dark';
  }

  // Recording State Machine
  function updateRecordingUI() {
    const pill = document.getElementById('recStatusPill');
    const statusText = document.getElementById('recStatusText');
    const ring = document.getElementById('recBtnRing');
    const btn = document.getElementById('mainRecordBtn');
    const btnLabel = document.getElementById('mainRecordBtnLabel');
    const ctaLabel = document.getElementById('recCtaLabel');
    const activeCtrls = document.getElementById('activeRecControls');
    const pauseText = document.getElementById('pauseResumeText');
    const floating = document.getElementById('floatingOverlay');

    pill.className = 'sim-pill';
    ring.className = 'record-btn-ring';
    btn.className = 'record-main-btn';
    btn.disabled = false;

    if (state.recState === 'IDLE') {
      statusText.textContent = 'IDLE • Prototype Simulation';
      btnLabel.textContent = 'REC';
      ctaLabel.textContent = 'Start Recording';
      activeCtrls.style.display = 'none';
      floating.classList.remove('visible');
    } else if (state.recState === 'PREPARING') {
      pill.classList.add('preparing');
      statusText.textContent = 'PREPARING • Initializing MediaCodec...';
      btnLabel.textContent = '...';
      ctaLabel.textContent = 'Preparing Capture Session';
      btn.disabled = true;
      activeCtrls.style.display = 'none';
    } else if (state.recState === 'RECORDING') {
      pill.classList.add('recording');
      ring.classList.add('recording');
      btn.classList.add('recording');
      statusText.textContent = `RECORDING (${state.consentScope}) • Sim`;
      btnLabel.textContent = 'STOP';
      ctaLabel.textContent = 'Recording in Progress';
      activeCtrls.style.display = 'flex';
      pauseText.textContent = 'Pause';
      if (state.floatingControls) floating.classList.add('visible');
    } else if (state.recState === 'PAUSED') {
      pill.classList.add('paused');
      ring.classList.add('paused');
      btn.classList.add('paused');
      statusText.textContent = 'PAUSED • Prototype Simulation';
      btnLabel.textContent = 'STOP';
      ctaLabel.textContent = 'Recording Paused';
      activeCtrls.style.display = 'flex';
      pauseText.textContent = 'Resume';
      if (state.floatingControls) floating.classList.add('visible');
    } else if (state.recState === 'STOPPING') {
      pill.classList.add('stopping');
      statusText.textContent = 'STOPPING • Muxing MP4...';
      btnLabel.textContent = '...';
      ctaLabel.textContent = 'Finalizing Recording';
      btn.disabled = true;
      activeCtrls.style.display = 'none';
      floating.classList.remove('visible');
    } else if (state.recState === 'SAVED') {
      pill.classList.add('saved');
      statusText.textContent = 'SAVED • Added to Library';
      btnLabel.textContent = 'REC';
      ctaLabel.textContent = 'Start New Recording';
      activeCtrls.style.display = 'none';
      floating.classList.remove('visible');
    }
  }

  function startRecordingFlow() {
    if (state.recState !== 'IDLE' && state.recState !== 'SAVED') {
      if (state.recState === 'RECORDING' || state.recState === 'PAUSED') {
        stopRecording();
      }
      return;
    }
    openModal('modal-consent');
  }

  function beginSimulatedRecording() {
    closeModal('modal-consent');
    if (state.recState === 'PREPARING' || state.recState === 'RECORDING') return;

    state.recState = 'PREPARING';
    state.elapsedSec = 0;
    document.getElementById('recTimer').textContent = '00:00:00';
    document.getElementById('floatTimer').textContent = '00:00';
    updateRecordingUI();

    setTimeout(() => {
      state.recState = 'RECORDING';
      updateRecordingUI();
      clearInterval(state.timerInterval);
      state.timerInterval = setInterval(() => {
        if (state.recState === 'RECORDING') {
          state.elapsedSec += 1;
          document.getElementById('recTimer').textContent = fmtDuration(state.elapsedSec);
          document.getElementById('floatTimer').textContent = fmtShortDuration(state.elapsedSec);
        }
      }, 1000);
    }, 650);
  }

  function togglePauseResume() {
    if (state.recState === 'RECORDING') {
      state.recState = 'PAUSED';
      updateRecordingUI();
      showToast('Recording paused');
    } else if (state.recState === 'PAUSED') {
      state.recState = 'RECORDING';
      updateRecordingUI();
      showToast('Recording resumed');
    }
  }

  function stopRecording() {
    if (state.recState !== 'RECORDING' && state.recState !== 'PAUSED') return;
    clearInterval(state.timerInterval);
    state.recState = 'STOPPING';
    updateRecordingUI();

    setTimeout(() => {
      const duration = Math.max(state.elapsedSec, 3);
      const resData = RES_INFO[state.resolution];
      const fpsScale = state.fps === 60 ? 1 : state.fps === 30 ? 0.65 : 0.52;
      const sizeMB = Number(((resData.mbPerMin / 60) * duration * fpsScale).toFixed(1));
      const now = new Date();
      const stamp = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}${String(now.getSeconds()).padStart(2, '0')}`;
      const fileName = state.namingPattern.startsWith('ScreenRec')
        ? `ScreenRec_${String(state.media.length + 1).padStart(3, '0')}.mp4`
        : `CapturePro_${stamp}.mp4`;

      const newItem = {
        id: 'm_' + Date.now(),
        type: 'video',
        name: fileName,
        durationSec: duration,
        sizeMB: Math.max(sizeMB, 1.2),
        resolution: resData.label,
        fps: state.fps,
        dateTs: Date.now(),
        dateLabel: 'Just now',
        thumb: makeThumbnail(fileName, `${resData.label} • ${state.fps} FPS • Simulated`, '#6366f1', false),
      };

      state.media.unshift(newItem);
      state.activeItemId = newItem.id;
      state.recState = 'SAVED';
      updateRecordingUI();
      renderMediaLists();
      updateStorageUI();

      // Populate Completion Modal
      document.getElementById('compThumb').innerHTML = `<img src="${newItem.thumb}" alt="Thumbnail" />`;
      document.getElementById('compName').textContent = newItem.name;
      document.getElementById('compMeta').textContent = `${fmtShortDuration(newItem.durationSec)} • ${newItem.sizeMB} MB • ${newItem.resolution}`;
      openModal('modal-completion');
    }, 600);
  }

  // Screenshot Simulation
  function takeScreenshot() {
    const flash = document.getElementById('flashOverlay');
    flash.classList.remove('flash');
    void flash.offsetWidth;
    flash.classList.add('flash');

    const now = new Date();
    const stamp = `${now.getFullYear()}${String(now.getMonth() + 1).padStart(2, '0')}${String(now.getDate()).padStart(2, '0')}_${String(now.getHours()).padStart(2, '0')}${String(now.getMinutes()).padStart(2, '0')}${String(now.getSeconds()).padStart(2, '0')}`;
    const name = `Screenshot_${stamp}.png`;
    const thumb = makeThumbnail('Captured Screen', `${RES_INFO[state.resolution].dims} • PNG`, '#10b981', true);

    const item = {
      id: 's_' + Date.now(),
      type: 'screenshot',
      name,
      durationSec: 0,
      sizeMB: 2.8,
      resolution: RES_INFO[state.resolution].dims,
      fps: 0,
      dateTs: Date.now(),
      dateLabel: 'Just now',
      thumb,
    };

    state.media.unshift(item);
    state.activeItemId = item.id;
    renderMediaLists();
    updateStorageUI();

    document.getElementById('shotPreviewImg').src = thumb;
    document.getElementById('shotNameLabel').textContent = name;
    openModal('modal-screenshot');
  }

  // Synthesize a real playable WebM demo video from Canvas for the built-in player
  let activeVideoUrl = null;
  function playMediaItem(item) {
    if (item.type === 'screenshot') {
      state.activeItemId = item.id;
      document.getElementById('shotPreviewImg').src = item.thumb;
      document.getElementById('shotNameLabel').textContent = item.name;
      openModal('modal-screenshot');
      return;
    }

    document.getElementById('playerTitle').textContent = item.name;
    document.getElementById('playerMeta').textContent = `${item.resolution} • ${item.fps || 60} FPS • ${item.sizeMB} MB`;
    const videoEl = document.getElementById('videoPlayerEl');
    openModal('modal-player');

    if (activeVideoUrl) {
      URL.revokeObjectURL(activeVideoUrl);
      activeVideoUrl = null;
    }

    // Generate short animated demo stream if MediaRecorder is available
    try {
      const c = document.createElement('canvas');
      c.width = 480;
      c.height = 270;
      const ctx = c.getContext('2d');
      let frame = 0;
      const draw = () => {
        ctx.fillStyle = '#0f172a';
        ctx.fillRect(0, 0, 480, 270);
        ctx.fillStyle = '#6366f1';
        ctx.fillRect(28, 28, 120, 24);
        ctx.fillStyle = '#fff';
        ctx.font = 'bold 12px Inter, sans-serif';
        ctx.fillText('CAPTUREPRO DEMO', 36, 44);
        ctx.font = 'bold 18px Inter, sans-serif';
        ctx.fillText(item.name.slice(0, 26), 28, 110);
        ctx.fillStyle = '#94a3b8';
        ctx.font = '13px monospace';
        ctx.fillText(`Simulated Playback • Frame ${frame}`, 28, 140);
        ctx.fillStyle = '#3b82f6';
        ctx.fillRect(28 + ((frame * 8) % 360), 175, 64, 8);
        frame++;
      };
      draw();

      if (typeof MediaRecorder !== 'undefined' && c.captureStream) {
        const stream = c.captureStream(24);
        const recorder = new MediaRecorder(stream);
        const chunks = [];
        recorder.ondataavailable = (e) => e.data.size && chunks.push(e.data);
        recorder.onstop = () => {
          const blob = new Blob(chunks, { type: 'video/webm' });
          activeVideoUrl = URL.createObjectURL(blob);
          videoEl.src = activeVideoUrl;
          videoEl.play().catch(() => {});
        };
        recorder.start();
        const anim = setInterval(draw, 42);
        setTimeout(() => {
          clearInterval(anim);
          if (recorder.state !== 'inactive') recorder.stop();
        }, 1200);
      } else {
        videoEl.poster = item.thumb;
      }
    } catch (_) {
      videoEl.poster = item.thumb;
    }
  }

  // Render Recent Recordings & Library
  function createMediaCardHTML(item) {
    const isVid = item.type === 'video';
    const badge = isVid ? fmtShortDuration(item.durationSec) : 'PNG';
    return `
      <div class="media-card" data-id="${item.id}">
        <div class="media-thumb">
          <img src="${item.thumb}" alt="${item.name}" />
          <span class="thumb-badge">${badge}</span>
        </div>
        <div class="media-info">
          <div class="media-title">${item.name}</div>
          <div class="media-meta">
            <span>${item.sizeMB} MB</span>
            <span>•</span>
            <span>${item.resolution}</span>
            <span>•</span>
            <span>${item.dateLabel}</span>
          </div>
        </div>
        <div class="media-actions">
          <button class="mini-btn play" data-action="play" data-id="${item.id}" type="button" title="${isVid ? 'Play' : 'View'}">
            <i data-lucide="${isVid ? 'play' : 'eye'}" style="width:15px;height:15px;"></i>
          </button>
          <button class="mini-btn" data-action="rename" data-id="${item.id}" type="button" title="Rename">
            <i data-lucide="pencil" style="width:14px;height:14px;"></i>
          </button>
          <button class="mini-btn" data-action="details" data-id="${item.id}" type="button" title="Details">
            <i data-lucide="info" style="width:14px;height:14px;"></i>
          </button>
          <button class="mini-btn" data-action="delete" data-id="${item.id}" type="button" title="Delete">
            <i data-lucide="trash-2" style="width:14px;height:14px;"></i>
          </button>
        </div>
      </div>
    `;
  }

  function renderMediaLists() {
    const recentEl = document.getElementById('recentRecordingsList');
    const recentItems = state.media.slice(0, 3);
    if (recentItems.length === 0) {
      recentEl.innerHTML = `<div class="empty-state"><p>No recordings yet. Tap Start Recording above.</p></div>`;
    } else {
      recentEl.innerHTML = recentItems.map(createMediaCardHTML).join('');
    }

    const libEl = document.getElementById('libraryList');
    let filtered = state.media.filter((m) => {
      const tabMatch = state.libTab === 'all' || m.type === state.libTab;
      const searchMatch = m.name.toLowerCase().includes(state.libSearch.toLowerCase());
      return tabMatch && searchMatch;
    });

    if (state.libSort === 'newest') filtered.sort((a, b) => b.dateTs - a.dateTs);
    else if (state.libSort === 'oldest') filtered.sort((a, b) => a.dateTs - b.dateTs);
    else if (state.libSort === 'size') filtered.sort((a, b) => b.sizeMB - a.sizeMB);

    if (filtered.length === 0) {
      libEl.innerHTML = `
        <div class="empty-state">
          <i data-lucide="film" style="width:32px;height:32px;opacity:0.4;"></i>
          <p>No matching media found.</p>
        </div>
      `;
    } else {
      libEl.innerHTML = filtered.map(createMediaCardHTML).join('');
    }

    if (window.lucide) window.lucide.createIcons();
  }

  // Storage Calculations
  function updateStorageUI() {
    const vidMB = state.media.filter((m) => m.type === 'video').reduce((s, m) => s + m.sizeMB, 0);
    const shotMB = state.media.filter((m) => m.type === 'screenshot').reduce((s, m) => s + m.sizeMB, 0);
    const totalGB = 128.0;
    const sysGB = 74.2;
    const vidGB = vidMB / 1024;
    const shotGB = shotMB / 1024;
    const usedGB = sysGB + vidGB + shotGB;
    const freeGB = Math.max(totalGB - usedGB, 0);

    document.getElementById('cfgStorageVal').textContent = `${freeGB.toFixed(1)} GB Free`;
    document.getElementById('dashStorageSummary').textContent = `${usedGB.toFixed(1)} GB used of 128 GB`;
    document.getElementById('dashStorageFree').textContent = `${freeGB.toFixed(1)} GB free`;
    document.getElementById('storAvailVal').textContent = `${freeGB.toFixed(1)} GB`;
    document.getElementById('storVideosVal').textContent = `${vidMB.toFixed(1)} MB`;
    document.getElementById('storShotsVal').textContent = `${shotMB.toFixed(1)} MB`;

    const vidPct = Math.max((vidGB / totalGB) * 100, vidMB > 0 ? 1.5 : 0);
    const shotPct = Math.max((shotGB / totalGB) * 100, shotMB > 0 ? 0.8 : 0);
    document.getElementById('dashSegVid').style.width = `${vidPct}%`;
    document.getElementById('dashSegShot').style.width = `${shotPct}%`;
    document.getElementById('storSegVid').style.width = `${vidPct}%`;
    document.getElementById('storSegShot').style.width = `${shotPct}%`;
  }

  // Sync Audio UI
  function syncAudioUI() {
    document.getElementById('internalAudioToggle').checked = state.internalAudio;
    document.getElementById('micAudioToggle').checked = state.micAudio;

    const internalEffective = state.internalAudio && !state.simInternalBlocked;
    const internalStatusEl = document.getElementById('internalAudioStatus');
    if (state.simInternalBlocked) {
      internalStatusEl.textContent = 'Unavailable — Blocked by active app policy';
      internalStatusEl.style.color = 'var(--amber)';
    } else {
      internalStatusEl.textContent = state.internalAudio
        ? 'Supported (Android 10+ AudioPlaybackCapture)'
        : 'Disabled by user';
      internalStatusEl.style.color = '';
    }

    const micStatusEl = document.getElementById('micAudioStatus');
    micStatusEl.textContent = state.micAudio ? `Enabled (${state.micSource})` : 'Disabled by user';

    let modeLabel = 'Muted (No Audio)';
    let detail = 'Both internal audio and microphone are disabled.';
    if (internalEffective && state.micAudio) {
      modeLabel = 'Internal Audio + Microphone';
      detail = 'Both sources active. Native Android mixes AudioPlaybackCapture and AudioRecord where supported.';
    } else if (internalEffective && !state.micAudio) {
      modeLabel = 'Internal Audio Only';
      detail = 'Capturing device playback via AudioPlaybackCapture when permitted by the target app.';
    } else if (!internalEffective && state.micAudio) {
      modeLabel = state.simInternalBlocked && state.internalAudio
        ? 'Microphone Only (Internal Blocked)'
        : 'Microphone Only';
      detail = state.simInternalBlocked && state.internalAudio
        ? 'Internal audio is restricted by the active app. Recording continues with Microphone input.'
        : 'Capturing external/voice audio via Android AudioRecord.';
    } else if (state.simInternalBlocked && state.internalAudio && !state.micAudio) {
      modeLabel = 'Audio Unavailable';
      detail = 'Internal audio is blocked by app policy and Microphone is disabled. Enable Microphone to record voice.';
    }

    document.getElementById('activeAudioSummary').textContent = modeLabel;
    document.getElementById('activeAudioDetail').textContent = detail;
    document.getElementById('heroAudioChip').textContent = modeLabel;
    document.getElementById('cfgAudioVal').textContent = modeLabel;
    document.getElementById('cfgMicVal').textContent = state.micAudio ? `On (${state.micSource})` : 'Off';
    document.getElementById('setAudioSub').textContent = modeLabel;
  }

  // Sync Quality & Orientation UI
  function syncQualityUI() {
    if (state.simCapFallback && state.resolution === '1440p') {
      state.resolution = '1080p';
      showToast('Device cap simulated: fell back to 1080p Full HD');
    }

    document.querySelectorAll('#resGrid .select-pill').forEach((p) => {
      p.classList.toggle('active', p.dataset.res === state.resolution);
    });
    document.querySelectorAll('#fpsGrid .select-pill').forEach((p) => {
      p.classList.toggle('active', Number(p.dataset.fps) === state.fps);
    });
    document.querySelectorAll('#orientGrid .select-pill').forEach((p) => {
      p.classList.toggle('active', p.dataset.orient === state.orientation);
    });

    const resInfo = RES_INFO[state.resolution];
    const fpsFactor = state.fps === 60 ? 1 : state.fps === 30 ? 0.65 : 0.52;
    const estMB = Math.round(resInfo.mbPerMin * fpsFactor);

    document.getElementById('estUsageVal').textContent = `~${estMB} MB / min`;
    document.getElementById('heroQualityChip').textContent = `${resInfo.label} • ${state.fps} FPS`;
    document.getElementById('heroOrientChip').textContent = state.orientation;
    document.getElementById('cfgResVal').textContent = resInfo.label;
    document.getElementById('cfgFpsVal').textContent = `${state.fps} FPS`;
    document.getElementById('setQualitySub').textContent = `${resInfo.label} • ${state.fps} FPS • ${state.orientation}`;
  }

  // Sync Facecam UI
  function syncFacecamUI() {
    const bubble = document.getElementById('facecamBubble');
    bubble.classList.toggle('visible', state.facecamEnabled);
    bubble.classList.toggle('rounded-rect', state.facecamShape === 'rounded-rect');
    bubble.classList.toggle('with-border', state.facecamBorder);

    // Position presets
    bubble.style.top = '';
    bubble.style.bottom = '';
    bubble.style.left = '';
    bubble.style.right = '';
    if (state.facecamPos === 'top-left') { bubble.style.top = '96px'; bubble.style.left = '14px'; }
    else if (state.facecamPos === 'top-right') { bubble.style.top = '96px'; bubble.style.right = '14px'; }
    else if (state.facecamPos === 'bottom-left') { bubble.style.bottom = '84px'; bubble.style.left = '14px'; }
    else { bubble.style.bottom = '84px'; bubble.style.right = '14px'; }

    document.querySelectorAll('#facecamShapeGrid .select-pill').forEach((p) => {
      p.classList.toggle('active', p.dataset.shape === state.facecamShape);
    });
    document.querySelectorAll('#facecamPosGrid .select-pill').forEach((p) => {
      p.classList.toggle('active', p.dataset.pos === state.facecamPos);
    });

    const label = state.facecamEnabled
      ? `On (${state.facecamShape === 'circle' ? 'Circle' : 'Rounded'})`
      : 'Off';
    document.getElementById('cfgFacecamVal').textContent = label;
    document.getElementById('setFacecamSub').textContent = state.facecamEnabled ? 'Enabled (Draggable)' : 'Disabled';
  }

  // Make an element draggable inside deviceShell
  function makeDraggable(el) {
    let dragging = false;
    let startX = 0, startY = 0, origLeft = 0, origTop = 0;

    el.addEventListener('pointerdown', (e) => {
      if (e.target.closest('button')) return;
      dragging = true;
      el.setPointerCapture(e.pointerId);
      const shell = document.getElementById('deviceShell').getBoundingClientRect();
      const rect = el.getBoundingClientRect();
      startX = e.clientX;
      startY = e.clientY;
      origLeft = rect.left - shell.left;
      origTop = rect.top - shell.top;
      el.style.right = 'auto';
      el.style.bottom = 'auto';
      el.style.left = origLeft + 'px';
      el.style.top = origTop + 'px';
    });

    el.addEventListener('pointermove', (e) => {
      if (!dragging) return;
      const shell = document.getElementById('deviceShell').getBoundingClientRect();
      const maxX = shell.width - el.offsetWidth - 8;
      const maxY = shell.height - el.offsetHeight - 74;
      const nx = Math.max(8, Math.min(maxX, origLeft + (e.clientX - startX)));
      const ny = Math.max(34, Math.min(maxY, origTop + (e.clientY - startY)));
      el.style.left = nx + 'px';
      el.style.top = ny + 'px';
    });

    el.addEventListener('pointerup', () => { dragging = false; });
    el.addEventListener('pointercancel', () => { dragging = false; });
  }

  // Error Simulator
  function showErrorDialog(errId) {
    const err = ERROR_CATALOG.find((e) => e.id === errId);
    if (!err) return;
    document.getElementById('errorModalTitle').textContent = err.title;
    document.getElementById('errorModalDesc').textContent = err.desc;
    const actBtn = document.getElementById('errorActionBtn');
    actBtn.textContent = err.actionLabel;
    actBtn.onclick = () => {
      closeModal('modal-error');
      err.action();
    };
    openModal('modal-error');
  }

  function renderErrorSimMenu() {
    const list = document.getElementById('errorSimList');
    list.innerHTML = ERROR_CATALOG.map((e) => `
      <button class="settings-row clickable" data-err-id="${e.id}" type="button" style="border:1px solid var(--border-subtle);border-radius:10px;">
        <div>
          <span class="settings-label" style="font-size:12.5px;">${e.title}</span>
        </div>
        <i data-lucide="chevron-right" style="width:15px;height:15px;color:var(--text-muted);"></i>
      </button>
    `).join('');
    list.querySelectorAll('[data-err-id]').forEach((b) => {
      b.addEventListener('click', () => {
        closeModal('modal-errors-menu');
        showErrorDialog(b.dataset.errId);
      });
    });
    if (window.lucide) window.lucide.createIcons();
  }

  // Media Actions Delegation (Play, Rename, Delete, Details)
  function handleMediaAction(action, id) {
    const item = state.media.find((m) => m.id === id);
    if (!item) return;
    state.activeItemId = id;

    if (action === 'play') {
      playMediaItem(item);
    } else if (action === 'rename') {
      state.pendingRenameId = id;
      document.getElementById('renameInput').value = item.name;
      openModal('modal-rename');
    } else if (action === 'delete') {
      if (!state.deleteConfirm) {
        state.media = state.media.filter((m) => m.id !== id);
        renderMediaLists();
        updateStorageUI();
        showToast('File deleted');
        return;
      }
      state.pendingDeleteId = id;
      document.getElementById('deletePromptText').textContent = `Delete "${item.name}"? This cannot be undone.`;
      openModal('modal-delete');
    } else if (action === 'details') {
      document.getElementById('detailsBody').innerHTML = `
        <div><strong>File Name:</strong> ${item.name}</div>
        <div><strong>Type:</strong> ${item.type === 'video' ? 'MP4 Screen Recording' : 'PNG Screenshot'}</div>
        <div><strong>Resolution:</strong> ${item.resolution}</div>
        ${item.type === 'video' ? `<div><strong>Duration:</strong> ${fmtShortDuration(item.durationSec)} (${item.fps} FPS)</div>` : ''}
        <div><strong>File Size:</strong> ${item.sizeMB} MB</div>
        <div><strong>Saved:</strong> ${item.dateLabel}</div>
        <div><strong>Storage Path:</strong> ${state.saveDest === 'mediastore' ? 'MediaStore / Movies/CapturePro' : 'SAF / Custom Folder'}</div>
      `;
      openModal('modal-details');
    }
  }

  // Animated Mic VU Meter
  function startVuMeter() {
    const bars = document.querySelectorAll('#vuMeter .vu-bar');
    setInterval(() => {
      bars.forEach((bar) => {
        if (!state.micAudio) {
          bar.style.height = '10%';
          bar.style.opacity = '0.25';
        } else {
          const h = state.recState === 'RECORDING'
            ? 20 + Math.random() * 80
            : 12 + Math.random() * 35;
          bar.style.height = `${h}%`;
          bar.style.opacity = '1';
        }
      });
    }, 130);
  }

  // Init Event Listeners
  function init() {
    seedSampleMedia();
    renderMediaLists();
    updateStorageUI();
    syncAudioUI();
    syncQualityUI();
    syncFacecamUI();
    updateRecordingUI();
    renderErrorSimMenu();
    startVuMeter();

    // Navigation
    document.addEventListener('click', (e) => {
      const navBtn = e.target.closest('[data-nav]');
      if (navBtn) {
        navigateTo(navBtn.dataset.nav);
      }
      const closeBtn = e.target.closest('[data-close-modal]');
      if (closeBtn) {
        closeModal(closeBtn.dataset.closeModal);
      }
      const actionBtn = e.target.closest('[data-action][data-id]');
      if (actionBtn) {
        handleMediaAction(actionBtn.dataset.action, actionBtn.dataset.id);
      }
    });

    // Viewport Preview Switcher
    const shell = document.getElementById('deviceShell');
    const setVp = (cls, btnId) => {
      shell.classList.remove('size-360', 'size-412', 'size-full');
      shell.classList.add(cls);
      ['vp360Btn', 'vp412Btn', 'vpFullBtn'].forEach((id) => {
        document.getElementById(id).classList.toggle('active', id === btnId);
      });
    };
    document.getElementById('vp360Btn').addEventListener('click', () => setVp('size-360', 'vp360Btn'));
    document.getElementById('vp412Btn').addEventListener('click', () => setVp('size-412', 'vp412Btn'));
    document.getElementById('vpFullBtn').addEventListener('click', () => setVp('size-full', 'vpFullBtn'));

    // Theme Toggles
    document.getElementById('themeToggleBtn').addEventListener('click', () => {
      setTheme(state.theme === 'dark' ? 'light' : 'dark');
    });
    document.getElementById('settingsThemeToggle').addEventListener('change', (e) => {
      setTheme(e.target.checked ? 'dark' : 'light');
    });
    document.getElementById('headerSettingsBtn').addEventListener('click', () => navigateTo('screen-settings'));

    // Recording Controls
    document.getElementById('mainRecordBtn').addEventListener('click', startRecordingFlow);
    document.getElementById('qaRecordBtn').addEventListener('click', startRecordingFlow);
    document.getElementById('qaScreenshotBtn').addEventListener('click', takeScreenshot);
    document.getElementById('pauseResumeBtn').addEventListener('click', togglePauseResume);
    document.getElementById('stopRecBtn').addEventListener('click', stopRecording);

    // Floating Controls
    document.getElementById('floatPauseBtn').addEventListener('click', togglePauseResume);
    document.getElementById('floatStopBtn').addEventListener('click', stopRecording);
    document.getElementById('floatShotBtn').addEventListener('click', takeScreenshot);
    makeDraggable(document.getElementById('floatingOverlay'));
    makeDraggable(document.getElementById('facecamBubble'));

    // Consent Modal
    document.getElementById('consentEntireBtn').addEventListener('click', () => {
      state.consentScope = 'Entire Screen';
      document.getElementById('consentEntireBtn').classList.add('active');
      document.getElementById('consentAppBtn').classList.remove('active');
    });
    document.getElementById('consentAppBtn').addEventListener('click', () => {
      state.consentScope = 'Single App';
      document.getElementById('consentAppBtn').classList.add('active');
      document.getElementById('consentEntireBtn').classList.remove('active');
    });
    document.getElementById('consentCancelBtn').addEventListener('click', () => {
      closeModal('modal-consent');
      showErrorDialog('proj_denied');
    });
    document.getElementById('consentStartBtn').addEventListener('click', beginSimulatedRecording);

    // Completion Modal Actions
    document.getElementById('compPlayBtn').addEventListener('click', () => {
      closeModal('modal-completion');
      handleMediaAction('play', state.activeItemId);
    });
    document.getElementById('compRenameBtn').addEventListener('click', () => {
      closeModal('modal-completion');
      handleMediaAction('rename', state.activeItemId);
    });
    document.getElementById('compShareBtn').addEventListener('click', () => showToast('Android Share Sheet opened (Simulated)'));
    document.getElementById('compDeleteBtn').addEventListener('click', () => {
      closeModal('modal-completion');
      handleMediaAction('delete', state.activeItemId);
    });

    // Screenshot Modal Actions
    document.getElementById('shotShareBtn').addEventListener('click', () => showToast('Shared screenshot (Simulated)'));
    document.getElementById('shotRenameBtn').addEventListener('click', () => {
      closeModal('modal-screenshot');
      handleMediaAction('rename', state.activeItemId);
    });
    document.getElementById('shotDeleteBtn').addEventListener('click', () => {
      closeModal('modal-screenshot');
      handleMediaAction('delete', state.activeItemId);
    });

    // Rename & Delete Confirm
    document.getElementById('confirmRenameBtn').addEventListener('click', () => {
      const val = document.getElementById('renameInput').value.trim();
      if (!val) return;
      const item = state.media.find((m) => m.id === state.pendingRenameId);
      if (item) {
        item.name = val;
        renderMediaLists();
        showToast('File renamed');
      }
      closeModal('modal-rename');
    });

    document.getElementById('confirmDeleteBtn').addEventListener('click', () => {
      state.media = state.media.filter((m) => m.id !== state.pendingDeleteId);
      renderMediaLists();
      updateStorageUI();
      closeModal('modal-delete');
      showToast('File deleted');
    });

    // Video Player Close & Local File Load
    document.getElementById('closePlayerBtn').addEventListener('click', () => {
      const v = document.getElementById('videoPlayerEl');
      v.pause();
      closeModal('modal-player');
    });
    document.getElementById('localVideoInput').addEventListener('change', (e) => {
      const file = e.target.files && e.target.files[0];
      if (!file) return;
      const v = document.getElementById('videoPlayerEl');
      v.src = URL.createObjectURL(file);
      document.getElementById('playerTitle').textContent = file.name;
      v.play().catch(() => {});
    });

    // Library Tabs, Search, Sort
    document.querySelectorAll('[data-lib-tab]').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.libTab = btn.dataset.libTab;
        document.querySelectorAll('[data-lib-tab]').forEach((b) => b.classList.remove('active'));
        btn.classList.add('active');
        renderMediaLists();
      });
    });
    document.getElementById('libSearchInput').addEventListener('input', (e) => {
      state.libSearch = e.target.value;
      renderMediaLists();
    });
    document.getElementById('libSortSelect').addEventListener('change', (e) => {
      state.libSort = e.target.value;
      renderMediaLists();
    });

    // Audio Settings
    document.getElementById('internalAudioToggle').addEventListener('change', (e) => {
      state.internalAudio = e.target.checked;
      syncAudioUI();
    });
    document.getElementById('micAudioToggle').addEventListener('change', (e) => {
      state.micAudio = e.target.checked;
      syncAudioUI();
    });
    document.getElementById('simInternalBlockedToggle').addEventListener('change', (e) => {
      state.simInternalBlocked = e.target.checked;
      syncAudioUI();
      if (state.simInternalBlocked) showErrorDialog('internal_unavail');
    });
    document.getElementById('micSourceSelect').addEventListener('change', (e) => {
      state.micSource = e.target.value;
      syncAudioUI();
    });

    // Quality & Orientation
    document.querySelectorAll('#resGrid .select-pill').forEach((btn) => {
      btn.addEventListener('click', () => {
        if (state.simCapFallback && btn.dataset.res === '1440p') {
          showErrorDialog('unsupported_res');
          return;
        }
        state.resolution = btn.dataset.res;
        syncQualityUI();
      });
    });
    document.querySelectorAll('#fpsGrid .select-pill').forEach((btn) => {
      btn.addEventListener('click', () => {
        if (state.simCapFallback && Number(btn.dataset.fps) === 60) {
          showErrorDialog('unsupported_fps');
          return;
        }
        state.fps = Number(btn.dataset.fps);
        syncQualityUI();
      });
    });
    document.querySelectorAll('#orientGrid .select-pill').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.orientation = btn.dataset.orient;
        syncQualityUI();
      });
    });
    document.getElementById('simCapFallbackToggle').addEventListener('change', (e) => {
      state.simCapFallback = e.target.checked;
      syncQualityUI();
    });

    // Facecam
    document.getElementById('facecamToggle').addEventListener('change', (e) => {
      state.facecamEnabled = e.target.checked;
      syncFacecamUI();
    });
    document.getElementById('facecamBorderToggle').addEventListener('change', (e) => {
      state.facecamBorder = e.target.checked;
      syncFacecamUI();
    });
    document.querySelectorAll('#facecamShapeGrid .select-pill').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.facecamShape = btn.dataset.shape;
        syncFacecamUI();
      });
    });
    document.querySelectorAll('#facecamPosGrid .select-pill').forEach((btn) => {
      btn.addEventListener('click', () => {
        state.facecamPos = btn.dataset.pos;
        syncFacecamUI();
      });
    });

    // Storage & Misc Settings
    document.getElementById('saveDestSelect').addEventListener('change', (e) => {
      state.saveDest = e.target.value;
      document.getElementById('saveDestSub').textContent =
        state.saveDest === 'mediastore' ? 'MediaStore • Movies/CapturePro' : 'SAF • User Selected Folder';
    });
    document.getElementById('namingPatternSelect').addEventListener('change', (e) => {
      state.namingPattern = e.target.value;
      document.getElementById('namingPatternSub').textContent = state.namingPattern;
    });
    document.getElementById('lowStorageWarnToggle').addEventListener('change', (e) => {
      state.lowStorageWarn = e.target.checked;
    });
    document.getElementById('deleteConfirmToggle').addEventListener('change', (e) => {
      state.deleteConfirm = e.target.checked;
    });
    document.getElementById('floatingToggle').addEventListener('change', (e) => {
      state.floatingControls = e.target.checked;
      if (!state.floatingControls) {
        document.getElementById('floatingOverlay').classList.remove('visible');
        showToast('Overlay disabled — using notification controls');
      } else if (state.recState === 'RECORDING' || state.recState === 'PAUSED') {
        document.getElementById('floatingOverlay').classList.add('visible');
      }
    });

    // Error Simulator Buttons
    document.getElementById('openErrorSimBtn').addEventListener('click', () => openModal('modal-errors-menu'));
    document.getElementById('settingsErrorTesterBtn').addEventListener('click', () => openModal('modal-errors-menu'));

    if (window.lucide) window.lucide.createIcons();
  }

  window.addEventListener('DOMContentLoaded', init);
})();
