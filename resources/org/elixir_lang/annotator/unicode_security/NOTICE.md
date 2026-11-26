# Unicode data for Elixir's identifier rules

Each `identifiers-<Unicode version>.tsv` beside this file lists the code points above 127 that Elixir's tokenizer
accepts in an identifier, with their script sets, for one Unicode version. `identifiers-<Unicode version>-unrestricted.tsv`
lists the oldest version's without UTS #39's restriction, as releases before 1.14 accept them. They were derived by
`src/org/elixir_lang/annotator/unicode_security/generate.exs`, which also writes this file, from the Unicode
Character Database files in `lib/elixir/unicode/` of
https://github.com/elixir-lang/elixir,
with a port of the table-building part of that repository's `lib/elixir/unicode/tokenizer.ex`. That file is
copyright 2021 The Elixir Team and 2012 Plataformatec, and licensed under the Apache License, Version 2.0, the
licence of this repository (`LICENSE`). The Unicode data is licensed under the Unicode License v3 below.

## Sources

| Unicode | Elixir | Commit | Copyright |
|---|---|---|---|
| 14.0 | 1.14.5 | [`81d6007410b3ae43e279cfb689bd03ffc5f06830`](https://github.com/elixir-lang/elixir/tree/81d6007410b3ae43e279cfb689bd03ffc5f06830) | © 2021 Unicode®, Inc. |
| 15.0 | 1.15.8 | [`29fdd09c0fcb5f029d43591665de35491f7378dd`](https://github.com/elixir-lang/elixir/tree/29fdd09c0fcb5f029d43591665de35491f7378dd) | © 2022 Unicode®, Inc. |
| 15.1 | 1.16.3, 1.17.3 | [`327063cc8485692f2b902553fb599a2bef5a0f8f`](https://github.com/elixir-lang/elixir/tree/327063cc8485692f2b902553fb599a2bef5a0f8f) | © 2023 Unicode®, Inc. |
| 16.0 | 1.18.4 | [`7b20c281d521aa7aa2ad2baa1e9ae6c579d79d0c`](https://github.com/elixir-lang/elixir/tree/7b20c281d521aa7aa2ad2baa1e9ae6c579d79d0c) | © 2024 Unicode®, Inc. |
| 17.0 | 1.19.5, 1.20.4 | [`d33fd8e413fe98e0e54dde7ef94fadb272b1ef67`](https://github.com/elixir-lang/elixir/tree/d33fd8e413fe98e0e54dde7ef94fadb272b1ef67) | © 2025 Unicode®, Inc. |

## Unicode License v3

    UNICODE LICENSE V3

    COPYRIGHT AND PERMISSION NOTICE

    Copyright © 1991-2026 Unicode, Inc.

    NOTICE TO USER: Carefully read the following legal agreement. BY
    DOWNLOADING, INSTALLING, COPYING OR OTHERWISE USING DATA FILES, AND/OR
    SOFTWARE, YOU UNEQUIVOCALLY ACCEPT, AND AGREE TO BE BOUND BY, ALL OF THE
    TERMS AND CONDITIONS OF THIS AGREEMENT. IF YOU DO NOT AGREE, DO NOT
    DOWNLOAD, INSTALL, COPY, DISTRIBUTE OR USE THE DATA FILES OR SOFTWARE.

    Permission is hereby granted, free of charge, to any person obtaining a
    copy of data files and any associated documentation (the "Data Files") or
    software and any associated documentation (the "Software") to deal in the
    Data Files or Software without restriction, including without limitation
    the rights to use, copy, modify, merge, publish, distribute, and/or sell
    copies of the Data Files or Software, and to permit persons to whom the
    Data Files or Software are furnished to do so, provided that either (a)
    this copyright and permission notice appear with all copies of the Data
    Files or Software, or (b) this copyright and permission notice appear in
    associated Documentation.

    THE DATA FILES AND SOFTWARE ARE PROVIDED "AS IS", WITHOUT WARRANTY OF ANY
    KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
    MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT OF
    THIRD PARTY RIGHTS.

    IN NO EVENT SHALL THE COPYRIGHT HOLDER OR HOLDERS INCLUDED IN THIS NOTICE
    BE LIABLE FOR ANY CLAIM, OR ANY SPECIAL INDIRECT OR CONSEQUENTIAL DAMAGES,
    OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS,
    WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION,
    ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THE DATA
    FILES OR SOFTWARE.

    Except as contained in this notice, the name of a copyright holder shall
    not be used in advertising or otherwise to promote the sale, use or other
    dealings in these Data Files or Software without prior written
    authorization of the copyright holder.
