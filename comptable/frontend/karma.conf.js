// Tests unitaires Angular : Karma + Jasmine (outillage standard d'Angular CLI), executes dans Chrome headless.
// Le Chrome du poste est utilise (variable CHROME_BIN) : aucun navigateur n'est telecharge.
module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: { jasmine: { random: false }, clearContext: false },
    reporters: ['progress'],
    browsers: ['ChromeHeadlessSansBac'],
    customLaunchers: {
      ChromeHeadlessSansBac: { base: 'ChromeHeadless', flags: ['--no-sandbox', '--disable-gpu'] },
    },
    restartOnFileChange: false,
    singleRun: true,
  });
};
