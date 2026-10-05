#!/usr/bin/env bash
# ¿La tag corresponde a una pre-release?
#
#   scripts/is-prerelease-tag.sh v1.6.2-rc.1   -> exit 0 (pre-release)
#   scripts/is-prerelease-tag.sh v1.6.1         -> exit 1 (estable)
#
# Existe por un motivo concreto: /releases/latest y releases/latest IGNORAN las
# pre-releases, así que es la única forma de que una RC no llegue a todos los usuarios.
#
# Regla: pre-release si el tag empieza por "bat-" (canal de pruebas) o si el sufijo
# tras la versión es un identificador de pre-release conocido de SemVer.
#
# OJO: NO se usa "cualquier guion es pre-release". El repo tiene tags como
# v1.6.0-stable, donde el guion es un canAL, no una pre-release: tratarla como tal
# escondería la release estable detrás de /releases/latest y nadie la vería.
#
# Uso:  if scripts/is-prerelease-tag.sh "$TAG"; then ...  fi
set -euo pipefail

tag="${1:-}"

if [ -z "$tag" ]; then
    echo "uso: $0 <tag>" >&2
    exit 2
fi

# Canal de pruebas: nunca es la release que deben ver los usuarios estables.
case "$tag" in
    bat-*)
        echo "canal bat-*: pre-release" >&2
        exit 0
        ;;
esac

# Se ignoran los prefijos de canal para dejar solo la parte SemVer.
bare="${tag#bat-}"
bare="${bare#v}"
bare="${bare#V}"

# Los metadatos de build (1.0.0-rc.1+build.5) no cambian el orden SemVer, pero romperían
# el ancla final de la expresión regular.
bare="${bare%%+*}"

# Identificadores de pre-release de SemVer, más los sufijos que ya usa el proyecto.
# Se admite un identificador pegado al número (rc1, preview2, alpha3): en SemVer "rc1" es un
# identificador válido, y tratarlo como estable publicaría una RC a todos los usuarios.
if [[ "$bare" =~ -(alpha|alfa|beta|rc|preview|pre|dev|nightly|snapshot|next)([0-9.-][0-9A-Za-z.-]*)?$ ]]; then
    echo "sufijo de pre-release: $bare" >&2
    exit 0
fi

exit 1