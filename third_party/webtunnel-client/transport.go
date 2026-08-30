package main

import (
	"encoding/base64"
	"fmt"
	"net"

	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/webtunnel/transport/httpupgrade"

	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/webtunnel/transport/tls"
)

type ClientConfig struct {
	RemoteAddresses []string

	Path          string
	TLSKind       string
	TLSServerName string

	ApprovedCert string
}

type Transport struct {
	config *ClientConfig
}

func NewWebTunnelClientTransport(config *ClientConfig) (Transport, error) {
	return Transport{config: config}, nil
}

func (t Transport) Dial() (net.Conn, error) {
	var conn net.Conn
	for _, addr := range t.config.RemoteAddresses {
		if tcpConn, err := net.Dial("tcp", addr); err == nil {
			conn = tcpConn
			break
		}
	}
	if conn == nil {
		return nil, fmt.Errorf("Can't connect to %v", t.config.RemoteAddresses)
	}
	if t.config.TLSKind != "" {
		conf := &tls.Config{ServerName: t.config.TLSServerName}
		if t.config.ApprovedCert != "" {
			conf.AllowInsecure = true
			decodedCert, err := base64.StdEncoding.DecodeString(t.config.ApprovedCert)
			if err != nil {
				return nil, fmt.Errorf("failed to decode approved certificate chain hash : %v", err)
			}
			conf.PinnedPeerCertificateChainSha256 = append(conf.PinnedPeerCertificateChainSha256, decodedCert)
		}
		if tlsTransport, err := tls.NewTLSTransport(conf); err != nil {
			return nil, err
		} else {
			if tlsConn, err := tlsTransport.Client(conn); err != nil {
				return nil, err
			} else {
				conn = tlsConn
			}
		}
	}
	upgradeConfig := httpupgrade.Config{Path: t.config.Path, Host: t.config.TLSServerName}
	if httpupgradeTransport, err := httpupgrade.NewHTTPUpgradeTransport(&upgradeConfig); err != nil {
		return nil, err
	} else {
		if httpUpgradeConn, err := httpupgradeTransport.Client(conn); err != nil {
			return nil, err
		} else {
			conn = httpUpgradeConn
		}
	}
	return conn, nil
}
