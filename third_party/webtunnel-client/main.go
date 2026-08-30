package main

import (
	"fmt"
	"io"
	"io/ioutil"
	"log"
	"net"
	"net/url"
	"os"
	"os/signal"
	"strings"
	"sync"
	"syscall"

	pt "gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/goptlib"
	"gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/webtunnel"
)

func main() {
	ptInfo, err := pt.ClientSetup(nil)
	if err != nil {
		log.Fatal(err)
	}
	if ptInfo.ProxyURL != nil {
		pt.ProxyError("proxy is not supported")
		os.Exit(1)
	}
	pt.ReportVersion("webtunnel", webtunnel.Version)

	listeners := make([]net.Listener, 0)
	shutdown := make(chan struct{})
	var wg sync.WaitGroup
	for _, methodName := range ptInfo.MethodNames {
		switch methodName {
		case "webtunnel":
			ln, err := pt.ListenSocks("tcp", "127.0.0.1:0")
			if err != nil {
				pt.CmethodError(methodName, err.Error())
				break
			}
			log.Printf("Started SOCKS listener at %v.", ln.Addr())
			go socksAcceptLoop(ln, shutdown, &wg)
			pt.Cmethod(methodName, ln.Version(), ln.Addr())
			listeners = append(listeners, ln)
		default:
			pt.CmethodError(methodName, "no such method")
		}
	}
	pt.CmethodsDone()

	sigChan := make(chan os.Signal, 1)
	signal.Notify(sigChan, syscall.SIGTERM)

	if os.Getenv("TOR_PT_EXIT_ON_STDIN_CLOSE") == "1" {
		go func() {
			if _, err := io.Copy(ioutil.Discard, os.Stdin); err != nil {
				log.Printf("calling io.Copy(ioutil.Discard, os.Stdin) returned error: %v", err)
			}
			log.Printf("synthesizing SIGTERM because of stdin close")
			sigChan <- syscall.SIGTERM
		}()
	}

	<-sigChan
	log.Println("stopping webtunnel")

	for _, ln := range listeners {
		ln.Close()
	}
	close(shutdown)
	wg.Wait()
	log.Println("webtunnel is done.")
}

func socksAcceptLoop(ln *pt.SocksListener, shutdown chan struct{}, wg *sync.WaitGroup) {
	defer ln.Close()
	for {
		conn, err := ln.AcceptSocks()
		if err != nil {
			if err, ok := err.(net.Error); ok && err.Temporary() {
				continue
			}
			log.Printf("SOCKS accept error: %s", err)
			break
		}
		log.Printf("SOCKS accepted: %v", conn.Req)
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer conn.Close()

			handler := make(chan struct{})
			go func() {
				defer close(handler)
				var config ClientConfig

				if urlStr, ok := conn.Req.Args.Get("url"); ok {
					parsed, err := url.Parse(urlStr)
					if err != nil {
						log.Printf("url parse error: %s", err)
						conn.Reject()
						return
					}
					defaultPort := ""
					switch parsed.Scheme {
					case "https":
						config.TLSKind = "tls"
						defaultPort = "443"
					case "http":
						config.TLSKind = ""
						defaultPort = "80"
					default:
						log.Printf("url parse error: unknown scheme")
						conn.Reject()
						return
					}
					config.Path = strings.TrimPrefix(parsed.EscapedPath(), "/")
					config.TLSServerName = parsed.Hostname()
					port := parsed.Port()
					if port == "" {
						port = defaultPort
					}

					config.RemoteAddresses, err = getAddressesFromHostname(parsed.Hostname(), port)
					if err != nil {
						log.Println(err)
						conn.Reject()
						return
					}
					config.TLSServerName = parsed.Hostname()
				}

				if tlsServerName, ok := conn.Req.Args.Get("servername"); ok {
					config.TLSServerName = tlsServerName
				}

				if approvedCert, ok := conn.Req.Args.Get("cert"); ok {
					config.ApprovedCert = approvedCert
				}

				transport, err := NewWebTunnelClientTransport(&config)
				if err != nil {
					log.Printf("transport error: %s", err)
					conn.Reject()
					return
				}

				sconn, err := transport.Dial()
				if err != nil {
					log.Printf("dial error: %s", err)
					conn.Reject()
					return
				}
				conn.Grant(nil)
				defer sconn.Close()
				copyLoop(conn, sconn)
			}()
			select {
			case <-shutdown:
				log.Println("Received shutdown signal")
			case <-handler:
				log.Println("Handler ended")
			}
		}()
	}
}

func copyLoop(socks, sfconn io.ReadWriter) {
	done := make(chan struct{}, 2)
	go func() {
		if _, err := io.Copy(socks, sfconn); err != nil {
			log.Printf("copying webtunnel to SOCKS resulted in error: %v", err)
		}
		done <- struct{}{}
	}()
	go func() {
		if _, err := io.Copy(sfconn, socks); err != nil {
			log.Printf("copying SOCKS to webtunnel resulted in error: %v", err)
		}
		done <- struct{}{}
	}()
	<-done
	log.Println("copy loop ended")
}

func getAddressesFromHostname(hostname, port string) ([]string, error) {
	addr, err := net.LookupHost(hostname)
	if err != nil {
		return nil, fmt.Errorf("Lookup error for host %s: %v", hostname, err)
	}
	var v4, v6 []string
	for _, a := range addr {
		ip := net.ParseIP(a)
		if ip == nil || ip.IsLoopback() || ip.IsUnspecified() || ip.IsMulticast() || ip.IsLinkLocalUnicast() || ip.IsPrivate() {
			continue
		}
		joined := net.JoinHostPort(ip.String(), port)
		if ip.To4() != nil {
			v4 = append(v4, joined)
		} else {
			v6 = append(v6, joined)
		}
	}
	addresses := v4
	if len(addresses) == 0 {
		addresses = v6
	}
	if len(addresses) == 0 {
		return nil, fmt.Errorf("Could not find any valid IP for %s", hostname)
	}
	return addresses, nil
}
