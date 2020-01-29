#! /usr/bin/env bash
add_header () {
# $1 is filename to process
# $2 is the component name we're dealing with
FILENAME=$(basename -- "$1")
FILENAME="${FILENAME%.*}"
COMPONENT=$2
FIRSTLINE=$(head -n 1 $1 | tr -dc '[:alnum:] -')
cat << EOF > $1
---
layout: toc_developer_documentation
title: Documentation for $COMPONENT Developer
link_title: ${FIRSTLINE##*( )}
permalink: /docs/reference-manual/languages/${COMPONENT//[^[:alnum:]]/-}/developer/$FILENAME
toc_group: ${COMPONENT//[^[:alnum:]]/-}
---
$(cat $1)
EOF
}

set -ex
WORK_DIR=$(mktemp -d)
# trap "rm -rf ${WORK_DIR}" EXIT

JS_SRC="${WORK_DIR}/js/docs/user"
JS_DST=docs/reference-manual/languages/js/developer
if [ ! -d ${JS_DST}/extra ]; then
  mkdir -p ${JS_DST}/extra;
fi
git clone --branch master  --depth 1 ssh://git@ol-bitbucket.us.oracle.com:7999/g/js.git "${WORK_DIR}/js"
cp "${JS_SRC}/"*.md "${JS_DST}/extra"
for file in ${JS_DST}/extra/*.md
do
 add_header $file JavaScript
done


# JVM
JVM_SRC="${WORK_DIR}/graal/compiler/docs"
JVM_DST=docs/reference-manual/languages/jvm/developer
if [ ! -d ${JVM_DST}/extra ]; then
  mkdir -p ${JVM_DST}/extra;
fi
git clone --branch master  --depth 1 ssh://git@ol-bitbucket.us.oracle.com:7999/g/graal.git "${WORK_DIR}/graal"
cp "${JVM_SRC}/"*.md "${JVM_DST}/extra"
for file in $JVM_DST/extra/*.md
do
 add_header $file JVM
done

# LLVM
LLVM_SRC="${WORK_DIR}/graal/sulong/docs"
LLVM_DST=docs/reference-manual/languages/llvm/developer
if [ ! -d ${LLVM_DST}/extra ]; then
  mkdir -p ${LLVM_DST}/extra;
fi
rm "${LLVM_SRC}/index.md"
cp "${LLVM_SRC}/"*.md "${LLVM_DST}/extra"
for file in $LLVM_DST/extra/*.md
do
 add_header $file LLVM
done

# Python
PY_SRC="${WORK_DIR}/graalpython/doc"
PY_DST=docs/reference-manual/languages/python/developer
if [ ! -d ${PY_DST}/extra ]; then
  mkdir -p ${PY_DST}/extra;
fi
git clone --branch master  --depth 1 ssh://git@ol-bitbucket.us.oracle.com:7999/g/graalpython.git "${WORK_DIR}/graalpython"
cp "${PY_SRC}/"*.md "${PY_DST}/extra"
for file in $PY_DST/extra/*.md
do
 add_header $file Python
done

# R
R_SRC="${WORK_DIR}/fastr/documentation"
R_DST=docs/reference-manual/languages/r/developer
if [ ! -d ${R_DST}/extra ]; then
  mkdir -p ${R_DST}/extra;
fi
git clone --branch master  --depth 1 ssh://git@ol-bitbucket.us.oracle.com:7999/g/fastr.git "${WORK_DIR}/fastr"
rm "${R_SRC}/README.md"
rm "${R_SRC}/Index.md"
cp "${R_SRC}/"*.md "${R_DST}/extra"
for file in $R_DST/extra/*.md
do
 add_header $file R
done

# Ruby
RUBY_SRC="${WORK_DIR}/ruby/doc/user"
RUBY_DST=docs/reference-manual/languages/ruby/developer
if [ ! -d ${RUBY_DST}/extra ]; then
  mkdir -p ${RUBY_DST}/extra;
fi
git clone --branch master  --depth 1 ssh://git@ol-bitbucket.us.oracle.com:7999/g/truffleruby.git "${WORK_DIR}/ruby"
cp "${RUBY_SRC}/"*.md "${RUBY_DST}/extra"
for file in $RUBY_DST/extra/*.md
do
 add_header $file Ruby
done

# Native Image
NI_SRC="${WORK_DIR}/graal/substratevm"
NI_DST=docs/reference-manual/native-image/developer
if [ ! -d ${NI_DST}/extra ]; then
  mkdir -p ${NI_DST}/extra;
fi
rm "${NI_SRC}/README.md"
cp "${NI_SRC}/"*.md "${NI_DST}/extra"
for file in $NI_DST/extra/*.md
do
 add_header $file "Native Image"
done
