# Contributing to Train2Track

Thank you for your interest. Train2Track is run by one person, and it handles reports
from real riders. The rules here are strict on purpose. Please read them before you
spend time on anything.

## What is welcome

- **Issues.** Bugs, wrong or missing data, crashes, unclear texts, ideas.
- **Small, agreed fixes** as pull requests, under the rules below.

## What is not accepted

- Pull requests without an issue in which the maintainer agreed to the change.
- Pull requests from someone who has not agreed to the contributor agreement.
- Anything that helps to evade ticket inspections or to travel without a ticket. The
  app informs passengers and reminds them to carry a valid ticket. That is its purpose
  and its limit.
- Anything that identifies a ticket inspector or any other person: names, photos,
  descriptions, free text in reports.
- New trackers, advertising or analytics software, logins or accounts, push
  notifications.
- Changes to the licence, the name, the logo, the application ids or the legal texts.

## The rules for a pull request

1. **Issue first.** Open an issue, describe the problem and what you want to change, and
   wait for the maintainer's answer. A pull request without that agreement is closed.
2. **Contributor agreement.** You must agree to [CLA.md](CLA.md) once, in your first
   pull request. Without it nothing can be merged.
3. **One change per pull request.** Keep it small and say what it does and why.
4. **Privacy comes first.** A change may not make the apps or the server collect, send
   or store more than before, and may not make data visible to more parties. If your
   change touches what is sent anywhere, say so clearly in the issue. Such a change also
   needs a new privacy policy and is decided by the maintainer alone.
5. **Both apps stay in step.** Android (`app/`) is the reference. A change of behaviour
   needs the same change on iOS (`ios/`), or a clear note of what is missing. The
   document names, field names and rules shared by the apps, the server and
   `firestore.rules` must stay identical everywhere.
6. **Tests pass.** Run the tests from the README. Add or update tests for the logic you
   change. A change to `firestore.rules` or to the report functions needs a case in
   `functions/test/`.
7. **No secrets, no config.** Never commit Firebase config files, keys, ids of a real
   project, advertising ids, or contact details.
8. **Texts.** Every text a user sees exists in English and German. These texts contain
   no hyphen or dash, with the two exceptions S-Bahn and U-Bahn. German says "Mail".
9. **Style.** Write code that reads like the code around it. Comments explain why, in
   plain words.
10. **Your own work.** Only submit what you wrote yourself and may license. Do not paste
    code from projects with another licence.

## What happens then

The maintainer reviews when time allows. A pull request can be changed, merged in
another form, or declined, also without a reason. Nobody is entitled to a merge, a
review or an answer by a certain date. Merged changes reach this repository with the
next published copy of the maintainer's working repository, so your commit may appear
as part of a larger one. Contributors are named in the release notes if they wish.

## Security problems

Never report them in an issue or a pull request. See [SECURITY.md](SECURITY.md).

## Conduct

Be kind and factual. See [CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md).
