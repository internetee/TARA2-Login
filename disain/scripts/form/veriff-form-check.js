(function () {
    var timeout = 5000;
    var interval;

    try {
        var value = document.body.getAttribute("data-check-form-refresh-rate");
        var number = new Number(value);

        if (number >= 100) {
            timeout = number;
        }
    } catch (e) {
    }

    setTimeout(stopPolling, 360000);

    const csrfToken = document.querySelector("input[name='_csrf']").getAttribute("value");
    const sessionUrl = document.querySelector("#session-url").textContent;
    const sessionId = document.querySelector("#session-id").textContent;

    window.veriffSDK.createVeriffFrame({ 
        url: sessionUrl,
        lang: document.documentElement.lang,
        onEvent: handleVeriffEvent
    });

    function handleVeriffEvent(msg) {
        switch(msg) {
            case "CANCELED":
                cancelVeriff();
                break;
            case "FINISHED":
                initVeriff();
                startPolling();
                break;
        }
    }

    function stopPolling() {
        clearInterval(interval);
    }

    function startPolling() {
        interval = setInterval(function () {
            var xhttp = new XMLHttpRequest();
            xhttp.onreadystatechange = function () {
                if (this.readyState !== 4) return;
                var pollResponse = JSON.parse(this.responseText);

                if (this.status === 200 && pollResponse["status"] === 'COMPLETED') {
                    clearInterval(interval);
                    var form = document.createElement("form");
                    form.method = "POST";
                    form.action = "/auth/accept";

                    var input = document.createElement("input");
                    input.setAttribute("type", "hidden");
                    input.setAttribute("name", "_csrf");
                    input.setAttribute("value", csrfToken);
                    form.appendChild(input);
                    document.body.appendChild(form);
                    form.submit();
                } else if (this.status === 200 && pollResponse["status"] === 'PENDING') {
                    console.log(this.responseText);
                } else {
                    clearInterval(interval);

                    document.querySelector(".c-tab-login__main").classList.add('hidden');
                    document.querySelector("#veriff-error").classList.remove('hidden');
                    document.querySelector("#error-message").innerHTML = pollResponse["message"];

                    if (pollResponse["reportable"]) {
                        document.querySelector("#error-incident-number").innerHTML = pollResponse["incident_nr"];

                        var errorReportUrl = document.querySelector("#error-report-url").href;
                        errorReportUrl = errorReportUrl.replace("{1}", pollResponse["message"]);
                        errorReportUrl = errorReportUrl.replace("{2}", pollResponse["incident_nr"]);
                        document.querySelector("#error-report-url").href = errorReportUrl;

                        var errorReportNotification = document.querySelector("#error-report-notification").innerHTML;
                        errorReportNotification = errorReportNotification.replace("{1}", pollResponse["incident_nr"]);
                        document.querySelector("#error-report-notification").innerHTML = errorReportNotification;
                    } else {
                        document.querySelector("#error-incident-number-wrapper").classList.add('hidden');
                        document.querySelector("#error-report-url").classList.add('hidden');
                    }
                }
            };
            xhttp.open('GET', '/auth/veriff/poll', true);
            xhttp.setRequestHeader('Accept', 'application/json;charset=UTF-8');
            xhttp.send();

        }, timeout);
    } 

    async function initVeriff() {
        document.querySelector("#instruction").classList.add('hidden');
        document.querySelector("#veriff-wait-decision").classList.remove('hidden');

        const response = await fetch('/auth/veriff/init', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json',
                'X-CSRF-TOKEN': csrfToken
            },
            body: JSON.stringify({
                sessionId: sessionId,
                sessionUrl: sessionUrl
            })
        });
    }

    function cancelVeriff() {
        var form = document.createElement("form");
        form.method = "POST";
        form.action = "/auth/veriff/cancel";
        var input = document.createElement("input");
        input.setAttribute("type", "hidden");
        input.setAttribute("name", "_csrf");
        input.setAttribute("value", csrfToken);
        form.appendChild(input);
        document.body.appendChild(form);
        form.submit();
    }
})();