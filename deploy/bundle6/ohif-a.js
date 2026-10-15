// OHIF config for Hospital A — Colombo National Hospital
//
// !! IMPORTANT — replace INSTANCE_A_IP with THIS instance's public IP !!
//
// CORS lessons learned:
//   - NEVER use localhost or Docker container names here.
//     The browser (doctor's PC) fetches WADO pixel data directly.
//     localhost = the browser machine, not the server.
//   - dicomweb-proxy handles CORS headers for OHIF requests.
//   - Orthanc also needs CORS enabled (see docker-compose) because
//     the proxy forwards WADO requests to Orthanc with the browser's Origin.
//   - qidoSupportsIncludeField must be false for dicomweb-proxy.
//   - singlepart: 'bulkdata,video,pdf' avoids multipart parse errors.

window.config = {
  routerBasename: '/',
  extensions: [],
  modes: [],
  showStudyList: true,
  maxNumberOfWebWorkers: 3,
  showWarningMessageForCrossOrigin: false,
  showCPUFallbackMessage: false,
  useSharedArrayBuffer: 'FALSE',

  // ── Hospital A branding (Colombo National Hospital — blue theme) ──────────
  whiteLabeling: {
    createLogoComponentFn: function(React) {
      return React.createElement(
        'div',
        { style: { display:'flex', alignItems:'center', gap:'10px', color:'#fff', fontFamily:'sans-serif' } },
        React.createElement(
          'div',
          { style: { background:'#1565C0', borderRadius:'6px', width:'32px', height:'32px',
                     display:'flex', alignItems:'center', justifyContent:'center', fontSize:'18px' } },
          '🏥'
        ),
        React.createElement(
          'div', null,
          React.createElement('div',
            { style: { fontWeight:'700', fontSize:'14px', lineHeight:'1.2' } },
            'Colombo National Hospital'),
          React.createElement('div',
            { style: { fontSize:'11px', color:'#90CAF9', lineHeight:'1.2' } },
            'Sri Lanka Digital Health · PACS')
        )
      );
    },
  },

  dataSources: [
    {
      namespace: '@ohif/extension-default.dataSourcesModule.dicomweb',
      sourceName: 'dicomweb',
      configuration: {
        friendlyName: 'Colombo National Hospital PACS',
        name: 'CNH',
        // All three must point to dicomweb-proxy PUBLIC IP:port
        // Replace INSTANCE_A_IP with actual IP e.g. 3.110.15.3
        wadoUriRoot:  'http://INSTANCE_A_IP:8090',
        qidoRoot:     'http://INSTANCE_A_IP:8090/dicom-web',
        wadoRoot:     'http://INSTANCE_A_IP:8090/dicom-web',
        qidoSupportsIncludeField: false,
        imageRendering:      'wadors',
        thumbnailRendering:  'wadors',
        enableStudyLazyLoad: true,
        supportsFuzzyMatching: false,
        supportsWildcard:    false,
        singlepart:          'bulkdata,video,pdf',
      },
    },
  ],
  defaultDataSourceName: 'dicomweb',
};
