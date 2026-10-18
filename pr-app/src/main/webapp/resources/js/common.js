// Behaviour shared by all JSP pages, kept out of the HTML so the Content-Security-Policy can forbid inline scripts.
(function () {
    // <select data-autosubmit>: submit its form when the choice changes (e.g. the hospital switch in the header)
    document.addEventListener('change', function (e) {
        if (e.target.matches && e.target.matches('[data-autosubmit]') && e.target.form) {
            e.target.form.submit();
        }
    });
    // <form data-confirm="Question?">: ask before submitting
    document.addEventListener('submit', function (e) {
        var question = e.target.getAttribute && e.target.getAttribute('data-confirm');
        if (question && !window.confirm(question)) {
            e.preventDefault();
        }
    });
    // <button data-print>: print the page
    document.addEventListener('click', function (e) {
        if (e.target.closest && e.target.closest('[data-print]')) {
            window.print();
        }
    });
})();
