// OHIF config for Hospital B — Kandy Teaching Hospital
//
// !! IMPORTANT — replace INSTANCE_B_IP with THIS instance's public IP !!
//
// Same CORS rules as Instance A:
//   - Use public IP, never localhost or Docker hostnames.
//   - dicomweb-proxy at port 8090 handles CORS and routes WADO to orthanc-b.

window.config = {
  routerBasename: '/',
  extensions: [],
  modes: [],
  showStudyList: true,
  maxNumberOfWebWorkers: 3,
  showWarningMessageForCrossOrigin: false,
  showCPUFallbackMessage: false,
  useSharedArrayBuffer: 'FALSE',

  // ── Hospital B branding (Kandy Teaching Hospital — burgundy/red theme) ────
  whiteLabeling: {
    createLogoComponentFn: function(React) {
      return React.createElement(
        'div',
        { style: { display:'flex', alignItems:'center', gap:'10px', color:'#fff', fontFamily:'sans-serif' } },
        React.createElement(
          'div',
          { style: { background:'#7B1FA2', borderRadius:'6px', width:'32px', height:'32px',
                     display:'flex', alignItems:'center', justifyContent:'center', fontSize:'18px' } },
          '🏥'
        ),
        React.createElement(
          'div', null,
          React.createElement('div',
            { style: { fontWeight:'700', fontSize:'14px', lineHeight:'1.2' } },
            'Kandy Teaching Hospital'),
          React.createElement('div',
            { style: { fontSize:'11px', color:'#CE93D8', lineHeight:'1.2' } },
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
        friendlyName: 'Kandy Teaching Hospital PACS',
        name: 'KTH',
        // Replace INSTANCE_B_IP with actual IP e.g. 3.7.22.100
        wadoUriRoot:  'http://INSTANCE_B_IP:8090',
        qidoRoot:     'http://INSTANCE_B_IP:8090/dicom-web',
        wadoRoot:     'http://INSTANCE_B_IP:8090/dicom-web',
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
