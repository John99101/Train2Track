# Security

Train2Track handles reports from real riders. If you find a way to read data that should
not be readable, to write as someone else, to get around the protection against misuse,
or any other weakness, please report it privately.

## How to report

Write to **info.train2track@gmail.com** with the subject "Security".

**Do not open an issue or a pull request for it, and do not describe it in public**
until it is fixed.

Please include:

- what the problem is and what someone could do with it
- the steps to reproduce it
- the version of the app or the commit of this repository
- how you would like to be named, if at all

You will get an answer as soon as possible. Train2Track is run by one person in his
spare time, so please allow a few days. You will be told when the problem is fixed, and
you are welcome to publish your finding after that.

## Rules for testing

- Test on your own Firebase project or on the local emulators (see the README), not on
  the live Train2Track service.
- Do not read, change or delete data of other users. If you come across such data, stop
  and report.
- Do not send false reports or votes to the live service, and do not send requests in
  bulk to it or to the data sources.
- No social engineering, and nothing aimed at the accounts or devices of the maintainer
  or of users.

Reports made in good faith within these rules are welcome.

## Supported versions

Only the newest version of the apps and the current state of this repository are
supported.
