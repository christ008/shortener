{{- /*
  The certificate of a workload of the stack, for step-ca (deploy/stack/overlays/compose.pki-ca.yaml). The subject, the names and the validity come from
  the request that deploy/stack/pki.sh makes on the node; what the identity may be used for is the one thing the request cannot say, and comes from
  --set eku=server|client|both, with the role in --set role=...

  The extension is certificatePolicies, made once (openssl asn1parse -genconf) and carried here as DER in base64: the policy 2.25.85272469670986707899094737998565028652
  (an arc that needs no registration), a pointer to docs/DEPLOY.md and a notice a person can read. It says what the certificate is, in the certificate.
*/ -}}
{
  "subject": {"organization": "Shortener", "organizationalUnit": {{ toJson .Insecure.User.role }}, "commonName": {{ toJson .Subject.CommonName }}},
  "sans": {{ toJson .SANs }},
  "keyUsage": ["digitalSignature"],
  "basicConstraints": {"isCA": false},
{{- if eq .Insecure.User.eku "server" }}
  "extKeyUsage": ["serverAuth"],
{{- else if eq .Insecure.User.eku "client" }}
  "extKeyUsage": ["clientAuth"],
{{- else }}
  "extKeyUsage": ["serverAuth", "clientAuth"],
{{- end }}
  "extensions": [{"id": "2.5.29.32", "critical": false, "value": "MIGqMIGnBhRpgYCm8Na69qKJxZGxmrnGifzmLDCBjjBLBggrBgEFBQcCARY/aHR0cHM6Ly9naXRodWIuY29tL2NocmlzdDAwOC9zaG9ydGVuZXIvYmxvYi9tYWluL2RvY3MvREVQTE9ZLm1kMD8GCCsGAQUFBwICMDMMMUludGVybmFsIHdvcmtsb2FkIGlkZW50aXR5IG9mIHRoZSBTaG9ydGVuZXIgc3RhY2s="}]
}
