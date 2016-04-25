var WebRtcMediaManager = function (localVideoPreview, remoteVideo) {
    var me = this;

    me.peerConnection = null;
    me.peerConnectionState = 'new';
    me.remoteAudioVideoMediaStream = null;
    me.remoteVideo = remoteVideo;
    me.localVideo = localVideoPreview;
    me.localVideo.volume = 0;
    me.isAudioMuted = 1;
    me.isVideoMuted = 1;
    //stun server by default
    //commented to speedup WebRTC call establishment
    //me.stunServer = "stun.l.google.com:19302";
};

WebRtcMediaManager.prototype.checkMediaDevices = function() {
    trace("checkMediaDevices, navigator.mediaDevices - " + navigator.mediaDevices);
    trace("checkMediaDevices, navigator.mediaDevices.getUserMedia - " + navigator.mediaDevices.getUserMedia);
    return !(navigator.mediaDevices === undefined || navigator.mediaDevices.getUserMedia === undefined);
};

WebRtcMediaManager.prototype.init = function () {
    trace("WebRtcMediaManager - init");
    var me = this;

    me.hasVideo = false;
    this.peerConnection = null;
    this.peerConnectionState = 'new';
    this.remoteAudioVideoMediaStream = null;
};

WebRtcMediaManager.prototype.close = function () {
    //Commented to prevent termination of rtcMediaManager after MSRP call
    trace("WebRtcMediaManager - close()");
    if (this.peerConnectionState != 'finished') {
        this.peerConnectionState = 'finished';
        if (this.peerConnection) {
            trace("WebRtcMediaManager - PeerConnection will be closed");
            this.peerConnection.close();
            this.remoteVideo.pause();
            this.remoteVideo.src = null;
        }
    } else {
        console.log("peerConnection already closed, do nothing!");
    }
};


WebRtcMediaManager.prototype.createPeerConnection = function () {
    trace("WebRtcMediaManager - createPeerConnection()");
    var application = this;
    if (application.stunServer !== undefined && application.stunServer.length > 0) {
        pc_config = {"iceServers": [
            {"url": "stun:" + application.stunServer}
        ]};
    } else {
        pc_config = {"iceServers": []};
    }
    this.peerConnection = new RTCPeerConnection(pc_config, {"optional": [
        {"DtlsSrtpKeyAgreement": flashphonerLoader.useDTLS}
    ]});

    this.peerConnection.onaddstream = function (event) {
        application.onOnAddStreamCallback(event);
    };


    this.peerConnection.onremovestream = function (event) {
        application.onOnRemoveStreamCallback(event);
    };
};

WebRtcMediaManager.prototype.onOnAddStreamCallback = function (event) {
    trace("WebRtcMediaManager - onOnAddStreamCallback(): event=" + event);
    trace("WebRtcMediaManager - onOnAddStreamCallback(): event=" + event.stream);
    trace("WebRtcMediaManager - onOnAddStreamCallback(): event=" + this.remoteVideo);
    if (this.peerConnection != null) {
        this.remoteAudioVideoMediaStream = event.stream;
        attachMediaStream(this.remoteVideo, this.remoteAudioVideoMediaStream);
    }
    else {
        console.warn("SimpleWebRtcSipPhone:onOnAddStreamCallback(): this.peerConnection is null, bug in state machine!, bug in state machine!");
    }
};

WebRtcMediaManager.prototype.onOnRemoveStreamCallback = function (event) {
    trace("WebRtcMediaManager - onOnRemoveStreamCallback(): event=" + event);
    if (this.peerConnection != null) {
        this.remoteAudioVideoMediaStream = null;
        this.remoteVideo.pause();
    } else {
        console.warn("SimpleWebRtcSipPhone:onOnRemoveStreamCallback(): this.peerConnection is null, bug in state machine!");
    }
};

WebRtcMediaManager.prototype.waitGatheringIce = function () {
    var me = this;
    if (me.peerConnection != null) {
        sendSdp = function () {
            if (me.peerConnection != null) {
                trace("WebRtcMediaManager - waitGatheringIce() iceGatheringState=" + me.peerConnection.iceGatheringState);
                if (me.peerConnection.iceGatheringState == "complete") {
                    trace("WebRtcMediaManager - setLocalSDP: sdp=" + me.peerConnection.localDescription.sdp);
                    if (me.peerConnectionState == 'preparing-offer') {
                        me.peerConnectionState = 'offer-sent';
                        me.createOfferCallback(me.peerConnection.localDescription.sdp);// + this.candidates);
                    }
                    else if (me.peerConnectionState == 'preparing-answer') {
                        me.peerConnectionState = 'established';
                        me.createAnswerCallback(me.peerConnection.localDescription.sdp);// + this.candidates);
                    }
                    else if (me.peerConnectionState == 'established') {
                    }
                    else {
                        console.log("WebRtcMediaManager - onIceCandidateCallback(): RTCPeerConnection bad state!");
                    }
                    clearInterval(me.iceIntervalId);
                }
            } else {
                clearInterval(me.iceIntervalId);
            }
        };
        me.iceIntervalId = setInterval(sendSdp, 500);

    }
    else {
        console.warn("WebRtcMediaManager - onIceCandidateCallback(): this.peerConnection is null, bug in state machine!");
    }
};

WebRtcMediaManager.prototype.mute = function () {
    if (this.localAudioStream) {
        this.localAudioStream.getAudioTracks()[0].enabled = false;
    }
    if (this.localAudioVideoStream) {
        this.localAudioVideoStream.getAudioTracks()[0].enabled = false;
    }
};

WebRtcMediaManager.prototype.unmute = function () {
    if (this.localAudioStream) {
        this.localAudioStream.getAudioTracks()[0].enabled = true;
    }
    if (this.localAudioVideoStream) {
        this.localAudioVideoStream.getAudioTracks()[0].enabled = true;
    }
};

WebRtcMediaManager.prototype.getAccessToAudioAndVideo = function () {
    var me = this;
    if (!me.localAudioVideoStream) {

        var mediaStream = function (stream) {
            attachMediaStream(me.localVideo, stream);
            me.localAudioVideoStream = stream;
            me.isAudioMuted = -1;
            me.isVideoMuted = -1;
        };

        var error = function (error) {
            addLogMessage("Failed to get access to local media. Error code was " + error.code + ".");
            closeInfoView(3000);
            me.isAudioMuted = 1;
            me.isVideoMuted = 1;
        };
        if (me.checkMediaDevices()) {
            navigator.mediaDevices.getUserMedia({audio: true})
                .then(mediaStream)
                .catch(error);
        } else {
            getUserMedia({audio: true, video: true}, mediaStream, error);
        }
    }
};


WebRtcMediaManager.prototype.getAccessToAudio = function () {
    var me = this;
    if (!me.localAudioStream) {

        var mediaStream = function (stream) {
            me.localAudioStream = stream;
            me.isAudioMuted = -1;
        };

        var error = function (error) {
            addLogMessage("Failed to get access to local media. Error code was " + error.code + ".");
            closeInfoView(3000);
            me.isAudioMuted = 1;
        };

        if (me.checkMediaDevices()) {
            navigator.mediaDevices.getUserMedia({audio: true})
                .then(mediaStream)
                .catch(error);
        } else {
            getUserMedia({audio: true}, mediaStream, error);
        }
    }
};

WebRtcMediaManager.prototype.getConstraints = function (receiveVideo) {
    var constraints = {};
    if (webrtcDetectedBrowser == "firefox") {
        constraints = {offerToReceiveAudio: true, offerToReceiveVideo: receiveVideo};
    } else {
        constraints = {optional: [], mandatory: {OfferToReceiveAudio: true, OfferToReceiveVideo: receiveVideo}};
    }
    return constraints;
}

WebRtcMediaManager.prototype.createOffer = function (createOfferCallback, hasVideo) {
    trace("WebRtcMediaManager - createOffer()");
    var me = this;
    try {
        if (me.getConnectionState() != "established") {
            trace("Connection state is not established. Initializing...");
            me.init();
        }
        var mandatory = {};
        if (me.peerConnection == null) {
            trace("peerConnection is null");
            me.createPeerConnection();
            if (hasVideo) {
                me.peerConnection.addStream(me.localAudioVideoStream);
            } else {
                me.peerConnection.addStream(me.localAudioStream);
            }
        }
        mandatory = me.getConstraints(hasVideo);
        me.createOfferCallback = createOfferCallback;
        me.peerConnection.createOffer(function (offer) {
            me.onCreateOfferSuccessCallback(offer);
        }, function (error) {
            me.onCreateOfferErrorCallback(error);
        }, mandatory);

    }
    catch (exception) {
        console.error("WebRtcMediaManager - createOffer(): catched exception:" + exception);
    }
};

WebRtcMediaManager.prototype.createAnswer = function (createAnswerCallback, hasVideo) {
    var me = this;
    trace("WebRtcMediaManager - createAnswer() me.getConnectionState(): "+me.getConnectionState()+" me.hasVideo: "+me.hasVideo);
    if (me.getConnectionState() != "established") {
        me.init();
    }
    try {

        if (me.peerConnection == null) {
            me.createPeerConnection();
            if (hasVideo) {
                me.peerConnection.addStream(me.localAudioVideoStream);
            } else {
                me.peerConnection.addStream(me.localAudioStream);
            }
        } else {
            if (hasVideo) {
                me.peerConnection.addStream(me.localVideoStream);
                me.hasVideo = true;
            } else {
                if (me.localVideoStream) {
                    me.peerConnection.removeStream(me.localVideoStream);
                }
                me.hasVideo = false;
            }
        }
        me.createAnswerCallback = createAnswerCallback;
        var sdpOffer = new RTCSessionDescription({
            type: 'offer',
            sdp: me.lastReceivedSdp
        });
        me.peerConnectionState = 'offer-received';
        me.peerConnection.setRemoteDescription(sdpOffer, function () {
            me.onSetRemoteDescriptionSuccessCallback();
        }, function (error) {
            me.onSetRemoteDescriptionErrorCallback(error);
        });
    }
    catch (exception) {
        console.error("WebRtcMediaManager - createAnswer(): catched exception:" + exception);
    }
};

WebRtcMediaManager.prototype.onCreateOfferSuccessCallback = function (offer) {
    trace("WebRtcMediaManager - onCreateOfferSuccessCallback this.peerConnection: "+this.peerConnection+" this.peerConnectionState: "+this.peerConnectionState);
    if (this.peerConnection != null) {
        if (this.peerConnectionState == 'new' || this.peerConnectionState == 'established') {
            var application = this;
            this.peerConnectionState = 'preparing-offer';
            this.peerConnection.setLocalDescription(offer, function () {
                application.onSetLocalDescriptionSuccessCallback(offer.sdp);
            }, function (error) {
                application.onSetLocalDescriptionErrorCallback(error);
            });
        }
        else {
            console.error("WebRtcMediaManager - onCreateOfferSuccessCallback(): RTCPeerConnection bad state!");
        }
    }
    else {
        console.warn("SimpleWebRtcSipPhone:onCreateOfferSuccessCallback(): this.peerConnection is null, bug in state machine!");
    }
};

WebRtcMediaManager.prototype.onSetLocalDescriptionSuccessCallback = function (sdp) {
    trace("WebRtcMediaManager - onSetLocalDescriptionSuccessCallback");
    if (webrtcDetectedBrowser == "firefox") {
        trace("WebRtcMediaManager - onSetLocalDescriptionSuccessCallback: sdp=" + sdp);
        if (this.peerConnectionState == 'preparing-offer') {
            trace("Current PeerConnectionState is 'preparing-offer' sending offer...");
            this.peerConnectionState = 'offer-sent';
            this.createOfferCallback(sdp);
        }
        else if (this.peerConnectionState == 'preparing-answer') {
            trace("Current PeerConnectionState is 'preparing-answer' going to established...");
            this.peerConnectionState = 'established';
            this.createAnswerCallback(sdp);
        }
    } else {
        this.waitGatheringIce();
    }
};

WebRtcMediaManager.prototype.getConnectionState = function () {
    return this.peerConnectionState;
};

WebRtcMediaManager.prototype.setRemoteSDP = function (sdp, isInitiator) {
    trace("WebRtcMediaManager - setRemoteSDP: isInitiator: "+isInitiator+" sdp=" + sdp);
    if (isInitiator) {
        var sdpAnswer = new RTCSessionDescription({
            type: 'answer',
            sdp: sdp
        });
        var application = this;
        this.peerConnectionState = 'answer-received';
        this.peerConnection.setRemoteDescription(sdpAnswer, function () {
            application.onSetRemoteDescriptionSuccessCallback();
        }, function (error) {
            application.onSetRemoteDescriptionErrorCallback(error);
        });
    } else {
        this.lastReceivedSdp = sdp;
    }
};

WebRtcMediaManager.prototype.onSetRemoteDescriptionSuccessCallback = function () {
    trace("onSetRemoteDescriptionSuccessCallback");
    if (this.peerConnection != null) {
        if (this.peerConnectionState == 'answer-received') {
            trace("Current PeerConnectionState is 'answer-received' changing the PeerConnectionState to 'established'");
            this.peerConnectionState = 'established';
        }
        else if (this.peerConnectionState == 'offer-received') {
            trace("Current PeerConnectionState is 'offer-received' creating appropriate answer...");
            var application = this;
            this.peerConnection.createAnswer(function (answer) {
                application.onCreateAnswerSuccessCallback(answer);
            }, function (error) {
                application.onCreateAnswerErrorCallback(error);
            });
        }
        else {
            console.log("WebRtcMediaManager - onSetRemoteDescriptionSuccessCallback(): RTCPeerConnection bad state!");
        }
    }
    else {
        console.warn("SimpleWebRtcSipPhone:onSetRemoteDescriptionSuccessCallback(): this.peerConnection is null, bug in state machine!");
    }
};


WebRtcMediaManager.prototype.onCreateAnswerSuccessCallback = function (answer) {
    trace("onCreateAnswerSuccessCallback "+this.peerConnection);
    if (this.peerConnection != null) {
        if (this.peerConnectionState == 'offer-received') {
            trace("Current PeerConnectionState is 'offer-received', preparing answer...");
            // Prepare answer.
            var application = this;
            this.peerConnectionState = 'preparing-answer';
            this.peerConnection.setLocalDescription(answer, function () {
                application.onSetLocalDescriptionSuccessCallback(answer.sdp);
            }, function (error) {
                application.onSetLocalDescriptionErrorCallback(error);
            });
        }
        else {
            console.log("WebRtcMediaManager - onCreateAnswerSuccessCallback(): RTCPeerConnection bad state!");
        }
    }
    else {
        console.warn("SimpleWebRtcSipPhone:onCreateAnswerSuccessCallback(): this.peerConnection is null, bug in state machine!");
    }
};

WebRtcMediaManager.prototype.setStunServer = function (server) {
    this.stunServer = server;
}

WebRtcMediaManager.prototype.requestStats = function () {
    var me = this;
    if (this.peerConnection && this.peerConnection.getRemoteStreams()[0] && webrtcDetectedBrowser == "chrome") {
        if (this.peerConnection.getStats) {
            this.peerConnection.getStats(function (rawStats) {
                var results = rawStats.result();
                var result = {};
                for (var i = 0; i < results.length; ++i) {
                    var resultPart = me.processGoogRtcStatsReport(results[i]);
                    if (resultPart != null) {
                        if (resultPart.type == "googCandidatePair") {
                            result.activeCandidate = resultPart;
                        } else if (resultPart.type == "ssrc") {
                            if (resultPart.packetsLost == -1) {
                                result.outgoingStream = resultPart;
                            } else {
                                result.incomingStream = resultPart;
                            }
                        }
                    }
                }
                notifyStats(result);
            }, function(error) {
                console.log("Error received " + error);
            });

        }
    } else if (this.peerConnection && this.peerConnection.getRemoteStreams()[0] && webrtcDetectedBrowser == "firefox") {
        if (this.peerConnection.getStats) {
            this.peerConnection.getStats(null, function (rawStats) {
                var result = {};
                for (var k in rawStats) {
                    if (rawStats.hasOwnProperty(k)) {
                        var resultPart = me.processRtcStatsReport(rawStats[k]);
                        if (resultPart != null) {
                            if (resultPart.type == "outboundrtp") {
                                result.outgoingStream = resultPart;
                            } else if (resultPart.type == "inboundrtp") {
                                result.incomingStream = resultPart;
                            }
                        }
                    }
                }
                notifyStats(result);
            }, function(error) {
                console.log("Error received " + error);
            });
        }
    }
}

WebRtcMediaManager.prototype.processRtcStatsReport = function (report) {
    /**
     * RTCStatsReport http://mxr.mozilla.org/mozilla-central/source/dom/webidl/RTCStatsReport.webidl
     */
    var result = null;
    if (report.type && (report.type == "outboundrtp" || report.type == "inboundrtp") && report.id.indexOf("rtcp") == -1) {
        result = {};
        for (var k in report) {
            if (report.hasOwnProperty(k)) {
                result[k] = report[k];
            }
        }
    }

    return result;
}

WebRtcMediaManager.prototype.processGoogRtcStatsReport = function (report) {
    /**
     * Report types: googComponent, googCandidatePair, googCertificate, googLibjingleSession, googTrack, ssrc
     */
    var gotResult = false;
    var result = null;
    if (report.type && report.type == "googCandidatePair") {
        //check if this is active pair
        if (report.stat("googActiveConnection") == "true") {
            gotResult = true;
        }
    }

    if (report.type && report.type == "ssrc") {
        gotResult = true;
    }

    if (gotResult) {
        //prepare object
        result = {};
        result.timestamp = report.timestamp;
        result.id = report.id;
        result.type = report.type;
        if (report.names) {
            var names = report.names();
            for (var i = 0; i < names.length; ++i) {
                var attrName = names[i];
                result[attrName] = report.stat(attrName);
            }
        }
    }
    return result;
}

WebRtcMediaManager.prototype.onCreateAnswerErrorCallback = function (error) {
    console.error("WebRtcMediaManager - onCreateAnswerErrorCallback(): error: " + error);
};
WebRtcMediaManager.prototype.onCreateOfferErrorCallback = function (error) {
    console.error("WebRtcMediaManager - onCreateOfferErrorCallback(): error: " + error);
};
WebRtcMediaManager.prototype.onSetLocalDescriptionErrorCallback = function (error) {
    console.error("WebRtcMediaManager - onSetLocalDescriptionErrorCallback(): error: " + error);
};
WebRtcMediaManager.prototype.onSetRemoteDescriptionErrorCallback = function (error) {
    console.error("WebRtcMediaManager - onSetRemoteDescriptionErrorCallback(): error: " + error);
};

WebRtcMediaManager.prototype.hasActiveAudioStream = function () {
    if (!this.remoteAudioVideoMediaStream){
        trace("WebRtcMediaManager - no audio tracks");
        return false;
    }
    var l = this.remoteAudioVideoMediaStream.getAudioTracks().length;
    trace("WebRtcMediaManager - hasAudio length: "+l);
    if (l){
        return true;
    }else{
        return false;
    }
};
