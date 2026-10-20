# Concentus (Opus codec, Java port)

`shared/src/main/java/org/concentus/` is a copy of the Concentus Java Opus implementation:
<https://github.com/lostromb/concentus> (Java/Concentus). It is compiled into DiPlay as the
software Opus **encoder** for the CarPlay microphone uplink.

Why it is vendored instead of taken from a dependency: the UNI-V head unit runs Android 9
(API 28), whose `MediaCodec` has an Opus decoder but no Opus encoder. The uplink therefore had no
way to encode the microphone, and the phone received no microphone audio at all. Concentus is
pure Java, so it works on any API level the app supports.

The files are unmodified, keep their original package (`org.concentus`) and their original
copyright headers.

## Licence

Copyright (c) by various holding parties, including (but not limited to):
Skype Limited, Xiph.Org Foundation, CSIRO, Microsoft Corporation,
Jean-Marc Valin, Gregory Maxwell, Mark Borgerding, Timothy B. Terriberry,
Logan Stromberg. All rights are reserved by their respective holders.

Redistribution and use in source and binary forms, with or without modification, are permitted
provided that the following conditions are met:

* Redistributions of source code must retain the above copyright notice, this list of conditions
  and the following disclaimer.
* Redistributions in binary form must reproduce the above copyright notice, this list of
  conditions and the following disclaimer in the documentation and/or other materials provided
  with the distribution.
* Neither the name of Internet Society, IETF or IETF Trust, nor the names of specific
  contributors, may be used to endorse or promote products derived from this software without
  specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR
IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR
CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY
WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
